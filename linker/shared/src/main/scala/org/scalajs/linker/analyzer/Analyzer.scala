/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker.analyzer

import scala.annotation.tailrec

import scala.collection.mutable
import scala.concurrent._

import scala.util.{Success, Failure}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import org.scalajs.ir
import ir.{ClassKind, Definitions}
import Definitions._

import org.scalajs.linker._
import org.scalajs.linker.standard._

import Analysis._

private final class Analyzer(config: CommonPhaseConfig,
    symbolRequirements: SymbolRequirement,
    allowAddingSyntheticMethods: Boolean,
    inputProvider: Analyzer.InputProvider,
    ec: ExecutionContext)
    extends Analysis {

  import Analyzer._

  private var objectClassInfo: ClassInfo = _
  private[this] val _classInfos = mutable.Map.empty[String, ClassLoadingState]

  private[this] val _errors = mutable.Buffer.empty[Error]

  private val workQueue = new WorkQueue(ec)

  private val fromAnalyzer = FromCore("analyzer")

  private[this] var _loadedClassInfos: scala.collection.Map[String, ClassInfo] = _

  def classInfos: scala.collection.Map[String, Analysis.ClassInfo] = _loadedClassInfos

  def errors: Seq[Error] = _errors

  def computeReachability(): Future[Unit] = {
    require(_classInfos.isEmpty, "Cannot run the same Analyzer multiple times")

    loadObjectClass(() => loadEverything())

    workQueue.join().map(_ => postLoad())(ec)
  }

  private def loadObjectClass(onSuccess: () => Unit): Unit = {
    implicit val from = fromAnalyzer

    /* Load the java.lang.Object class, and validate it
     * If it is missing or invalid, we're in deep trouble, and cannot continue.
     */
    inputProvider.loadInfo(Definitions.ObjectClass)(ec) match {
      case None =>
        _errors += MissingJavaLangObjectClass(fromAnalyzer)

      case Some(future) =>
        workQueue.enqueue(future) { data =>
          if (data.kind != ClassKind.Class || data.superClass.isDefined ||
            data.interfaces.nonEmpty) {
            _errors += InvalidJavaLangObjectClass(fromAnalyzer)
          } else {
            objectClassInfo = new ClassInfo(data,
                unvalidatedSuperClass = None,
                unvalidatedInterfaces = Nil, nonExistent = false)

            objectClassInfo.link()
            onSuccess()
          }
        }
    }
  }

  private def loadEverything(): Unit = {
    assert(objectClassInfo != null)

    implicit val from = fromAnalyzer

    /* Hijacked classes are always instantiated, because values of primitive
     * types are their instances.
     */
    for (hijacked <- HijackedClasses)
      lookupClass(hijacked)(_.instantiated())

    // External symbol requirements, including module initializers
    reachSymbolRequirement(symbolRequirements)

    // Entry points (top-level exports and static initializers)
    for (className <- inputProvider.classesWithEntryPoints())
      lookupClass(className)(_.reachEntryPoints())
  }

  private def postLoad(): Unit = {
    val infos = _classInfos.collect { case (k, i: ClassInfo) => (k, i) }

    assert(_errors.nonEmpty || infos.size == _classInfos.size,
        "unloaded classes in post load phase")

    _loadedClassInfos = infos

    // Reach additional data, based on reflection methods used
    reachDataThroughReflection(infos)

    // Make sure top-level export names do not conflict
    checkConflictingExports(infos)
  }

  private def reachSymbolRequirement(requirement: SymbolRequirement,
      optional: Boolean = false): Unit = {

    def withClass(className: String)(onSuccess: ClassInfo => Unit)(
        implicit from: From): Unit = {
      lookupClass(className, ignoreMissing = optional)(onSuccess)
    }

    def withMethod(className: String, methodName: String)(
        onSuccess: ClassInfo => Unit)(
        implicit from: From): Unit = {
      withClass(className) { clazz =>
        val doReach = !optional || clazz.tryLookupMethod(methodName).isDefined
        if (doReach)
          onSuccess(clazz)
      }
    }

    import SymbolRequirement.Nodes._

    requirement match {
      case AccessModule(origin, moduleName) =>
        implicit val from = FromCore(origin)
        withClass(moduleName)(_.accessModule())

      case InstantiateClass(origin, className, constructor) =>
        implicit val from = FromCore(origin)
        withMethod(className, constructor) { clazz =>
          clazz.instantiated()
          clazz.callMethodStatically(constructor)
        }

      case InstanceTests(origin, className) =>
        implicit val from = FromCore(origin)
        withClass(className)(_.useInstanceTests())

      case ClassData(origin, className) =>
        implicit val from = FromCore(origin)
        withClass(className)(_.accessData())

      case CallMethod(origin, className, methodName, statically) =>
        implicit val from = FromCore(origin)
        withMethod(className, methodName) { classInfo =>
          if (statically)
            classInfo.callMethodStatically(methodName)
          else
            classInfo.callMethod(methodName)
        }

      case CallStaticMethod(origin, className, methodName) =>
        implicit val from = FromCore(origin)
        withMethod(className, methodName)(_.callStaticMethod(methodName))

      case Optional(requirement) =>
        reachSymbolRequirement(requirement, optional = true)

      case Multiple(requirements) =>
        for (requirement <- requirements)
          reachSymbolRequirement(requirement, optional)

      case NoRequirement => // skip
    }
  }

  /** Reach additional class data based on reflection methods being used. */
  private def reachDataThroughReflection(classInfos: scala.collection.Map[String, ClassInfo]): Unit = {
    val classClassInfo = classInfos.get(Definitions.ClassClass)

    /* If Class.getSuperclass() is reachable, we can reach the data of all
     * superclasses of classes whose data we can already reach.
     */
    for {
      getSuperclassMethodInfo <-
        classClassInfo.flatMap(_.methodInfos.get("getSuperclass__jl_Class"))
      if getSuperclassMethodInfo.isReachable
    } {
      // calledFrom should always be nonEmpty if isReachable, but let's be robust
      implicit val from =
        getSuperclassMethodInfo.calledFrom.headOption.getOrElse(fromAnalyzer)
      for (classInfo <- classInfos.values.filter(_.isDataAccessed).toList) {
        @tailrec
        def loop(classInfo: ClassInfo): Unit = {
          classInfo.accessData()
          classInfo.superClass match {
            case Some(superClass) => loop(superClass)
            case None             =>
          }
        }
        loop(classInfo)
      }
    }
  }

  private def checkConflictingExports(classInfos: scala.collection.Map[String, ClassInfo]): Unit = {
    val namesAndInfos = for {
      info <- classInfos.values
      name <- info.topLevelExportNames
    } yield {
      name -> info
    }

    for {
      (name, targets) <- namesAndInfos.groupBy(_._1)
      if targets.size > 1
    } {
      _errors += ConflictingTopLevelExport(name, targets.map(_._2).toList)
    }
  }

  private def lookupClass(encodedName: String, ignoreMissing: Boolean = false)(
      onSuccess: ClassInfo => Unit)(implicit from: From): Unit = {
    lookupClassForLinking(encodedName, Set.empty) {
      case info: ClassInfo =>
        if (!info.nonExistent || !ignoreMissing) {
          info.link()
          onSuccess(info)
        }

      case CycleInfo(cycle, _) =>
        _errors += CycleInInheritanceChain(cycle, fromAnalyzer)
    }
  }

  private def lookupClassForLinking(encodedName: String,
      knownDescendants: Set[LoadingClass] = Set.empty)(
      onSuccess: LoadingResult => Unit): Unit = {

    _classInfos.get(encodedName) match {
      case None =>
        val loading = new LoadingClass(encodedName)
        loading.requestLink(knownDescendants)(onSuccess)

      case Some(loading: LoadingClass) =>
        loading.requestLink(knownDescendants)(onSuccess)

      case Some(info: ClassInfo) =>
        onSuccess(info)
    }
  }


  private sealed trait LoadingResult
  private sealed trait ClassLoadingState

  private case class CycleInfo(cycle: List[String], root: LoadingClass) extends LoadingResult

  private final class LoadingClass(encodedName: String) extends ClassLoadingState {
    private val promise = Promise[LoadingResult]()
    private var knownDescendants = Set[LoadingClass](this)

    _classInfos(encodedName) = this

    inputProvider.loadInfo(encodedName)(ec) match {
      case Some(future) =>
        workQueue.enqueue(future)(link(_, nonExistent = false))

      case None =>
        val data = createMissingClassInfo(encodedName)
        link(data, nonExistent = true)
    }

    def requestLink(knownDescendants: Set[LoadingClass])(onSuccess: LoadingResult => Unit): Unit = {
      if (knownDescendants.contains(this)) {
        onSuccess(CycleInfo(Nil, this))
      } else {
        this.knownDescendants ++= knownDescendants
        workQueue.enqueue(promise.future)(onSuccess)
      }
    }

    private def link(data: Infos.ClassInfo, nonExistent: Boolean): Unit = {
      lookupAncestors(data.superClass.toList ++ data.interfaces) { classes =>
        val (superClass, interfaces) =
          if (data.superClass.isEmpty) (None, classes)
          else (Some(classes.head), classes.tail)

        val info = new ClassInfo(data, superClass, interfaces, nonExistent)

        implicit val from = FromClass(info)
        classes.foreach(_.link())

        promise.success(info)
      } { cycleInfo =>
        val newInfo = cycleInfo match {
          case CycleInfo(_, null) => cycleInfo

          case CycleInfo(c, root) if root == this =>
            CycleInfo(encodedName :: c, null)

          case CycleInfo(c, root) =>
            CycleInfo(encodedName :: c, root)
        }

        promise.success(newInfo)
      }
    }

    private def lookupAncestors(encodedNames: List[String])(
        loaded: List[ClassInfo] => Unit)(cycle: CycleInfo => Unit): Unit = {
      encodedNames match {
        case first :: rest =>
          lookupClassForLinking(first, knownDescendants) {
            case c: CycleInfo => cycle(c)

            case ifirst: ClassInfo =>
              lookupAncestors(rest)(irest => loaded(ifirst :: irest))(cycle)
          }
        case Nil =>
          loaded(Nil)
      }
    }
  }

  private class ClassInfo(
      val data: Infos.ClassInfo,
      unvalidatedSuperClass: Option[ClassInfo],
      unvalidatedInterfaces: List[ClassInfo],
      val nonExistent: Boolean)
      extends Analysis.ClassInfo with ClassLoadingState with LoadingResult {

    var linkedFrom: List[From] = Nil

    val encodedName = data.encodedName
    val kind = data.kind
    val isAnyModuleClass =
      data.kind.hasModuleAccessor || data.kind == ClassKind.NativeJSModuleClass
    val isInterface = data.kind == ClassKind.Interface
    val isScalaClass = data.kind.isClass || data.kind == ClassKind.HijackedClass
    val isJSClass = data.kind.isJSClass
    val isJSType = data.kind.isJSType
    val isAnyClass = isScalaClass || isJSClass
    val isExported = data.isExported
    val topLevelExportNames = data.topLevelExportNames

    // Note: j.l.Object is special and is validated upfront

    val superClass: Option[ClassInfo] =
      if (encodedName == ObjectClass) unvalidatedSuperClass
      else validateSuperClass(unvalidatedSuperClass)

    val interfaces: List[ClassInfo] =
      if (encodedName == ObjectClass) unvalidatedInterfaces
      else validateInterfaces(unvalidatedInterfaces)

    val ancestors: List[ClassInfo] = {
      val parents = superClass ++: interfaces
      this +: parents.flatMap(_.ancestors).distinct
    }

    _classInfos(encodedName) = this

    def link()(implicit from: From): Unit = {
      if (nonExistent)
        _errors += MissingClass(this, from)

      linkedFrom ::= from
    }

    private[this] def validateSuperClass(superClass: Option[ClassInfo]): Option[ClassInfo] = {
      implicit def from = FromClass(this)

      kind match {
        case ClassKind.Class | ClassKind.ModuleClass | ClassKind.HijackedClass =>
          superClass.fold[Option[ClassInfo]] {
            _errors += MissingSuperClass(this, from)
            Some(objectClassInfo)
          } { superCl =>
            if (superCl.kind != ClassKind.Class) {
              _errors += InvalidSuperClass(superCl, this, from)
              Some(objectClassInfo)
            } else {
              superClass
            }
          }

        case ClassKind.Interface =>
          superClass.foreach { superCl =>
            _errors += InvalidSuperClass(superCl, this, from)
          }

          None

        case ClassKind.JSClass | ClassKind.JSModuleClass =>
          /* There is no correct fallback in case of error, here. The logical
           * thing to do would be to pick `js.Object`, but we cannot be sure
           * that `js.Object` and its inheritance chain are valid themselves.
           * So we just say superClass = None in invalid cases, and make sure
           * this does not blow up the rest of the analysis.
           */
          superClass.fold[Option[ClassInfo]] {
            _errors += MissingSuperClass(this, from)
            None
          } { superCl =>
            superCl.kind match {
              case ClassKind.JSClass | ClassKind.NativeJSClass =>
                superClass // ok
              case _ =>
                _errors += InvalidSuperClass(superCl, this, from)
                None
            }
          }

        case ClassKind.NativeJSClass | ClassKind.NativeJSModuleClass =>
          superClass.fold[Option[ClassInfo]] {
            _errors += MissingSuperClass(this, from)
            Some(objectClassInfo)
          } { superCl =>
            superCl.kind match {
              case ClassKind.JSClass | ClassKind.NativeJSClass =>
                superClass // ok
              case _ if superCl eq objectClassInfo =>
                superClass // ok
              case _ =>
                _errors += InvalidSuperClass(superCl, this, from)
                Some(objectClassInfo)
            }
          }

        case ClassKind.AbstractJSType =>
          superClass.flatMap { superCl =>
            superCl.kind match {
              case ClassKind.JSClass | ClassKind.NativeJSClass =>
                superClass // ok
              case _ if superCl eq objectClassInfo =>
                superClass // ok
              case _ =>
                _errors += InvalidSuperClass(superCl, this, from)
                None
            }
          }
      }
    }

    private[this] def validateInterfaces(interfaces: List[ClassInfo]): List[ClassInfo] = {
      implicit def from = FromClass(this)

      val validSuperIntfKind = kind match {
        case ClassKind.Class | ClassKind.ModuleClass |
            ClassKind.HijackedClass | ClassKind.Interface =>
          ClassKind.Interface
        case ClassKind.JSClass | ClassKind.JSModuleClass |
            ClassKind.NativeJSClass | ClassKind.NativeJSModuleClass |
            ClassKind.AbstractJSType =>
          ClassKind.AbstractJSType
      }

      interfaces.filter { superIntf =>
        if (superIntf.nonExistent) {
          // Remove it but do not report an additional error message
          false
        } else if (superIntf.kind != validSuperIntfKind) {
          _errors += InvalidImplementedInterface(superIntf, this, from)
          false
        } else {
          true
        }
      }
    }

    var isInstantiated: Boolean = false
    var isAnySubclassInstantiated: Boolean = false
    var isModuleAccessed: Boolean = false
    var areInstanceTestsUsed: Boolean = false
    var isDataAccessed: Boolean = false
    var isAnyStaticFieldReachable: Boolean = false

    var instantiatedFrom: List[From] = Nil

    /** List of all instantiated (Scala) subclasses of this Scala class/trait.
     *  For JS types, this always remains empty.
     */
    var instantiatedSubclasses: List[ClassInfo] = Nil
    var methodsCalledLog: List[(String, From)] = Nil

    lazy val (methodInfos, staticMethodInfos) = {
      val allInfos = for (methodData <- data.methods)
        yield (methodData.encodedName, new MethodInfo(this, methodData))
      val (staticMethodInfos, methodInfos) = allInfos.partition(_._2.isStatic)
      (mutable.Map(methodInfos: _*), mutable.Map(staticMethodInfos: _*))
    }

    def lookupConstructor(ctorName: String): MethodInfo = {
      methodInfos.get(ctorName).getOrElse {
        createNonExistentMethod(ctorName)
      }
    }

    def lookupMethod(methodName: String): MethodInfo = {
      tryLookupMethod(methodName).getOrElse {
        createNonExistentMethod(methodName)
      }
    }

    private def createNonExistentMethod(methodName: String): MethodInfo = {
      val syntheticData = createMissingMethodInfo(methodName)
      val m = new MethodInfo(this, syntheticData)
      m.nonExistent = true
      methodInfos += methodName -> m
      m
    }

    def tryLookupMethod(methodName: String): Option[MethodInfo] = {
      assert(isScalaClass || isInterface,
          s"Cannot call lookupMethod($methodName) on non Scala class $this")

      @tailrec
      def tryLookupInherited(ancestorInfo: ClassInfo): Option[MethodInfo] = {
        ancestorInfo.methodInfos.get(methodName) match {
          case Some(m) if !m.isAbstract =>
            Some(m)
          case _ =>
            ancestorInfo.superClass match {
              case Some(superClass) => tryLookupInherited(superClass)
              case None             => None
            }
        }
      }
      val existing =
        if (isScalaClass) tryLookupInherited(this)
        else methodInfos.get(methodName).filter(!_.isAbstract)

      if (!allowAddingSyntheticMethods) {
        existing
      } else if (existing.exists(m => !m.isDefaultBridge || m.owner == this)) {
        /* If we found a non-bridge, it must be the right target.
         * If we found a bridge directly in this class/interface, it must also
         * be the right target.
         */
        existing
      } else {
        // Try and find the target of a possible default bridge
        findDefaultTarget(methodName).fold {
          assert(existing.isEmpty)
          existing
        } { defaultTarget =>
          if (existing.exists(_.defaultBridgeTarget == defaultTarget.owner.encodedName)) {
            /* If we found an existing bridge targeting the right method, we
             * can reuse it.
             * We also get here with None when there is no target whatsoever.
             */
            existing
          } else {
            // Otherwise, create a new default bridge
            Some(createDefaultBridge(defaultTarget))
          }
        }
      }
    }

    /** Resolves an inherited default method.
     *
     *  This lookup is specified by the JVM resolution rules for default
     *  methods. See the `invokespecial` opcode in the JVM Specification
     *  version 8, Section 6.5:
     *  https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokespecial
     */
    private def findDefaultTarget(methodName: String): Option[MethodInfo] = {
      val candidates = for {
        intf <- ancestors if intf.isInterface
        m <- intf.methodInfos.get(methodName)
        if !m.isAbstract && !m.isDefaultBridge
      } yield m

      val notShadowed = candidates filterNot { m =>
        candidates exists { n =>
          (n ne m) && n.owner.ancestors.contains(m.owner)
        }
      }

      if (notShadowed.size > 1) {
        /* Deviation from the spec: if there are several targets, the spec
         * chooses one arbitrarily. However, unless the classpath is
         * manipulated and/or corrupted, this should not happen. The Java
         * *language* and compiler do not let this happen on their own.
         * Besides, the current implementation of the JVM throws an
         * IncompatibleClassChangeError when trying to resolve such ambiguous
         * references.
         * So we emit an error too, so that we can more easily discover bugs.
         * We use fromAnalyzer because we don't have any From here (we
         * shouldn't, since lookup methods are not supposed to produce errors).
         */
        _errors += ConflictingDefaultMethods(notShadowed, fromAnalyzer)
      }

      notShadowed.headOption
    }

    private def createDefaultBridge(target: MethodInfo): MethodInfo = {
      val methodName = target.encodedName
      val targetOwner = target.owner

      val syntheticInfo = makeSyntheticMethodInfo(
          encodedName = methodName,
          methodsCalledStatically = Map(
              targetOwner.encodedName -> List(methodName)))
      val m = new MethodInfo(this, syntheticInfo)
      m.syntheticKind = MethodSyntheticKind.DefaultBridge(
          targetOwner.encodedName)
      methodInfos += methodName -> m
      m
    }

    def tryLookupReflProxyMethod(proxyName: String): Option[MethodInfo] = {
      if (!allowAddingSyntheticMethods) {
        tryLookupMethod(proxyName)
      } else {
        methodInfos.get(proxyName).orElse {
          findReflectiveTarget(proxyName).map { reflectiveTarget =>
            createReflProxy(proxyName, reflectiveTarget.encodedName)
          }
        }
      }
    }

    private def findReflectiveTarget(proxyName: String): Option[MethodInfo] = {
      /* The lookup for a target method in this code implements the
       * algorithm defining `java.lang.Class.getMethod`. This mimics how
       * reflective calls are implemented on the JVM, at link time.
       *
       * We add a bit of guess-work for default methods, as the documentation
       * is very vague about them. Basically, we just take the first match in
       * `ancestors`, as it's easy, and we're in a gray area anyway. At least,
       * this will work when there is no overload.
       *
       * Caveat: protected methods are not ignored. This can only make an
       * otherwise invalid reflective call suddenly able to call a protected
       * method. It never breaks valid reflective calls. This could be fixed
       * if the IR retained the information that a method is protected.
       */

      val superClasses =
        Iterator.iterate(this)(_.superClass.orNull).takeWhile(_ ne null)
      val superClassesThenAncestors = superClasses ++ ancestors.iterator

      superClassesThenAncestors.map(_.findProxyMatch(proxyName)).collectFirst {
        case Some(m) => m
      }
    }

    private def findProxyMatch(proxyName: String): Option[MethodInfo] = {
      val candidates = methodInfos.valuesIterator.filter { m =>
        // TODO In theory we should filter out protected methods
        !m.isReflProxy && !m.isDefaultBridge && !m.isExported && !m.isAbstract &&
        reflProxyMatches(m.encodedName, proxyName)
      }.toSeq

      /* From the JavaDoc of java.lang.Class.getMethod:
       *
       *   If more than one [candidate] method is found in C, and one of these
       *   methods has a return type that is more specific than any of the
       *   others, that method is reflected; otherwise one of the methods is
       *   chosen arbitrarily.
       */

      val targets = candidates.filterNot { c =>
        val resultType = methodResultType(c.encodedName)
        candidates.exists { other =>
          (other ne c) &&
          isMoreSpecific(methodResultType(other.encodedName), resultType)
        }
      }

      /* This last step (chosen arbitrarily) causes some soundness issues of
       * the implementation of reflective calls. This is bug-compatible with
       * Scala/JVM.
       */
      targets.headOption
    }

    private def reflProxyMatches(methodName: String, proxyName: String): Boolean = {
      val sepPos = methodName.lastIndexOf("__")
      sepPos >= 0 && methodName.substring(0, sepPos + 2) == proxyName
    }

    private def methodResultType(methodName: String): ir.Types.TypeRef =
      decodeTypeRef(methodName.substring(methodName.lastIndexOf("__") + 2))

    private def isMoreSpecific(left: ir.Types.TypeRef,
        right: ir.Types.TypeRef): Boolean = {
      import ir.Types._

      def getClassInfo(name: String): Option[ClassInfo] = {
        // TODO: This is suspicious: It shouldn't be necessary.
        _classInfos.get(name).collect { case i: ClassInfo => i }
      }

      def classIsMoreSpecific(leftCls: String, rightCls: String): Boolean = {
        leftCls != rightCls && {
          val leftInfo = getClassInfo(leftCls)
          val rightInfo = getClassInfo(rightCls)
          leftInfo.zip(rightInfo).exists { case (l, r) =>
            l.ancestors.contains(r)
          }
        }
      }

      (left, right) match {
        case (ClassRef(leftCls), ClassRef(rightCls)) =>
          classIsMoreSpecific(leftCls, rightCls)
        case (ArrayTypeRef(leftBase, leftDepth), ArrayTypeRef(rightBase, rightDepth)) =>
          leftDepth == rightDepth && classIsMoreSpecific(leftBase, rightBase)
        case (ArrayTypeRef(_, _), ClassRef(ObjectClass)) =>
          true
        case _ =>
          false
      }
    }

    private def createReflProxy(proxyName: String,
        targetName: String): MethodInfo = {
      assert(this.isScalaClass,
          s"Cannot create reflective proxy in non-Scala class $this")

      val syntheticInfo = makeSyntheticMethodInfo(
          encodedName = proxyName,
          methodsCalled = Map(
              this.encodedName -> List(targetName)))
      val m = new MethodInfo(this, syntheticInfo)
      m.syntheticKind = MethodSyntheticKind.ReflectiveProxy(targetName)
      methodInfos += proxyName -> m
      m
    }

    def lookupStaticMethod(methodName: String): MethodInfo = {
      tryLookupStaticMethod(methodName).getOrElse {
        val syntheticData = createMissingStaticMethodInfo(methodName)
        val m = new MethodInfo(this, syntheticData)
        m.nonExistent = true
        staticMethodInfos += methodName -> m
        m
      }
    }

    def tryLookupStaticMethod(methodName: String): Option[MethodInfo] =
      staticMethodInfos.get(methodName)

    override def toString(): String = encodedName

    /** Start the reachability algorithm with the entry points of this class. */
    def reachEntryPoints(): Unit = {
      implicit val from = FromExports

      // Myself
      if (isExported) {
        if (isAnyModuleClass)
          accessModule()
        else
          instantiated()
      }

      // Static initializer
      if (!isJSType) {
        staticMethodInfos.get(StaticInitializerName).foreach {
          _.reachStatic()(fromAnalyzer)
        }
      }
    }

    def accessModule()(implicit from: From): Unit = {
      if (!isAnyModuleClass) {
        _errors += NotAModule(this, from)
      } else if (!isModuleAccessed) {
        isModuleAccessed = true

        if (kind != ClassKind.NativeJSModuleClass) {
          instantiated()
          if (isScalaClass)
            callMethodStatically("init___")
        }
      }
    }

    def instantiated()(implicit from: From): Unit = {
      instantiatedFrom ::= from

      val isNativeJSClass = kind == ClassKind.NativeJSClass

      /* TODO? When the second line is false, shouldn't this be a linking error
       * instead?
       */
      if (!isInstantiated &&
          (isScalaClass || isJSClass || isNativeJSClass)) {
        isInstantiated = true

        /* Reach referenced classes of non-static fields
         *
         * Note that the classes referenced by static fields are reached
         * implicitly by the call-sites that read or write the field: the
         * SelectStatic expression has the same type as the field.
         */
        for (className <- data.referencedFieldClasses) {
          if (!Definitions.PrimitiveClasses.contains(className))
            lookupClass(className)(_ => ())
        }

        if (isScalaClass) {
          accessData()

          val allMethodsCalledLogs = for (ancestor <- ancestors) yield {
            ancestor.subclassInstantiated()
            ancestor.instantiatedSubclasses ::= this
            ancestor.methodsCalledLog
          }

          for {
            log <- allMethodsCalledLogs
            logEntry <- log
          } {
            val methodName = logEntry._1
            implicit val from = logEntry._2
            callMethodResolved(methodName)
          }
        } else {
          assert(isJSClass || isNativeJSClass)

          subclassInstantiated()

          if (isJSClass) {
            superClass.foreach(_.instantiated())
            tryLookupStaticMethod(Definitions.StaticInitializerName).foreach {
              staticInit => staticInit.reachStatic()
            }
          }

          for (methodInfo <- staticMethodInfos.values) {
            if (methodInfo.isExported)
              methodInfo.reachStatic()(FromExports)
          }
          for (methodInfo <- methodInfos.values) {
            if (methodInfo.isExported)
              methodInfo.reach(this)(FromExports)
          }
        }
      }
    }

    private def subclassInstantiated()(implicit from: From): Unit = {
      instantiatedFrom ::= from
      if (!isAnySubclassInstantiated && (isScalaClass || isJSType)) {
        isAnySubclassInstantiated = true

        // Reach exported members
        if (!isJSClass) {
          implicit val from = FromExports
          for (methodInfo <- methodInfos.values) {
            if (methodInfo.isExported)
              callMethod(methodInfo.encodedName)
          }
        }
      }
    }

    def useInstanceTests()(implicit from: From): Unit = {
      if (!areInstanceTestsUsed)
        areInstanceTestsUsed = true
    }

    def accessData()(implicit from: From): Unit = {
      if (!isDataAccessed)
        isDataAccessed = true
    }

    def callMethod(methodName: String)(implicit from: From): Unit = {
      // Constructors must always be called statically
      assert(!isConstructorName(methodName),
          s"Trying to dynamically call the constructor $this.$methodName from $from")

      /* First add the call to the log, then fetch the instantiated subclasses,
       * then perform the resolved call. This order is important because,
       * during the resolved calls, new instantiated subclasses could be
       * detected, and those need to see the updated log, since the loop in
       * this method won't see them.
       */
      methodsCalledLog ::= ((methodName, from))
      val subclasses = instantiatedSubclasses
      for (subclass <- subclasses)
        subclass.callMethodResolved(methodName)
    }

    private def callMethodResolved(methodName: String)(
        implicit from: From): Unit = {
      if (isReflProxyName(methodName)) {
        tryLookupReflProxyMethod(methodName).foreach(_.reach(this))
      } else {
        lookupMethod(methodName).reach(this)
      }
    }

    def callMethodStatically(methodName: String)(implicit from: From): Unit = {
      if (isConstructorName(methodName)) {
        lookupConstructor(methodName).reachStatic()
      } else {
        assert(!isReflProxyName(methodName),
            s"Trying to call statically refl proxy $this.$methodName")
        lookupMethod(methodName).reachStatic()
      }
    }

    def callStaticMethod(methodName: String)(implicit from: From): Unit = {
      lookupStaticMethod(methodName).reachStatic()
    }
  }

  private class MethodInfo(val owner: ClassInfo,
      data: Infos.MethodInfo) extends Analysis.MethodInfo {

    val encodedName = data.encodedName
    val isStatic = data.isStatic
    val isAbstract = data.isAbstract
    val isExported = data.isExported
    val isReflProxy = isReflProxyName(encodedName)

    var isReachable: Boolean = false

    var calledFrom: List[From] = Nil
    var instantiatedSubclasses: List[ClassInfo] = Nil

    var nonExistent: Boolean = false

    var syntheticKind: MethodSyntheticKind = MethodSyntheticKind.None

    def isDefaultBridge: Boolean =
      syntheticKind.isInstanceOf[MethodSyntheticKind.DefaultBridge]

    /** Throws MatchError if `!isDefaultBridge`. */
    def defaultBridgeTarget: String = (syntheticKind: @unchecked) match {
      case MethodSyntheticKind.DefaultBridge(target) => target
    }

    override def toString(): String = s"$owner.$encodedName"

    def reachStatic()(implicit from: From): Unit = {
      assert(!isAbstract,
          s"Trying to reach statically the abstract method $this")

      checkExistent()

      calledFrom ::= from
      if (!isReachable) {
        isReachable = true
        doReach()
      }
    }

    def reach(inClass: ClassInfo)(implicit from: From): Unit = {
      assert(!isStatic,
          s"Trying to dynamically reach the static method $this")
      assert(!isAbstract,
          s"Trying to dynamically reach the abstract method $this")
      assert(owner.isAnyClass,
          s"Trying to dynamically reach the non-class method $this")
      assert(!isConstructorName(encodedName),
          s"Trying to dynamically reach the constructor $this")

      checkExistent()

      calledFrom ::= from
      instantiatedSubclasses ::= inClass

      if (!isReachable) {
        isReachable = true
        doReach()
      }
    }

    private def checkExistent()(implicit from: From) = {
      if (nonExistent)
        _errors += MissingMethod(this, from)
    }

    private[this] def doReach(): Unit = {
      implicit val from = FromMethod(this)

      for (moduleName <- data.accessedModules) {
        lookupClass(moduleName)(_.accessModule())
      }

      for (className <- data.instantiatedClasses) {
        lookupClass(className)(_.instantiated())
      }

      for (className <- data.usedInstanceTests) {
        if (!Definitions.PrimitiveClasses.contains(className))
          lookupClass(className)(_.useInstanceTests())
      }

      for (className <- data.accessedClassData) {
        if (!Definitions.PrimitiveClasses.contains(className))
          lookupClass(className)(_.accessData())
      }

      for (className <- data.referencedClasses) {
        if (!Definitions.PrimitiveClasses.contains(className))
          lookupClass(className)(_ => ())
      }

      /* `for` loops on maps are written with `while` loops to help the JIT
       * compiler to inline and stack allocate tuples created by the iterators
       */

      val staticFieldsReadIterator = data.staticFieldsRead.iterator
      while (staticFieldsReadIterator.hasNext) {
        val (className, fields) = staticFieldsReadIterator.next()
        if (fields.nonEmpty)
          lookupClass(className)(_.isAnyStaticFieldReachable = true)
      }

      val staticFieldsWrittenIterator = data.staticFieldsWritten.iterator
      while (staticFieldsWrittenIterator.hasNext) {
        val (className, fields) = staticFieldsWrittenIterator.next()
        if (fields.nonEmpty)
          lookupClass(className)(_.isAnyStaticFieldReachable = true)
      }

      val methodsCalledIterator = data.methodsCalled.iterator
      while (methodsCalledIterator.hasNext) {
        val (className, methods) = methodsCalledIterator.next()
        lookupClass(className) { classInfo =>
          for (methodName <- methods)
            classInfo.callMethod(methodName)
        }
      }

      val methodsCalledStaticallyIterator = data.methodsCalledStatically.iterator
      while (methodsCalledStaticallyIterator.hasNext) {
        val (className, methods) = methodsCalledStaticallyIterator.next()
        lookupClass(className) { classInfo =>
          for (methodName <- methods)
            classInfo.callMethodStatically(methodName)
        }
      }

      val staticMethodsCalledIterator = data.staticMethodsCalled.iterator
      while (staticMethodsCalledIterator.hasNext) {
        val (className, methods) = staticMethodsCalledIterator.next()
        lookupClass(className) { classInfo =>
          for (methodName <- methods)
            classInfo.callStaticMethod(methodName)
        }
      }
    }
  }

  private def createMissingClassInfo(encodedName: String): Infos.ClassInfo = {
    Infos.ClassInfo(
        encodedName = encodedName,
        isExported = false,
        kind = ClassKind.Class,
        superClass = Some("O"),
        interfaces = Nil,
        methods = List(createMissingMethodInfo("init___"))
    )
  }

  private def createMissingMethodInfo(
      encodedName: String): Infos.MethodInfo = {
    makeSyntheticMethodInfo(encodedName = encodedName)
  }

  private def createMissingStaticMethodInfo(
      encodedName: String): Infos.MethodInfo = {
    makeSyntheticMethodInfo(encodedName = encodedName, isStatic = true)
  }

  private def makeSyntheticMethodInfo(
      encodedName: String,
      isStatic: Boolean = false,
      methodsCalled: Map[String, List[String]] = Map.empty,
      methodsCalledStatically: Map[String, List[String]] = Map.empty,
      instantiatedClasses: List[String] = Nil
  ): Infos.MethodInfo = {
    Infos.MethodInfo(
        encodedName,
        isStatic,
        isAbstract = false,
        isExported = false,
        staticFieldsRead = Map.empty,
        staticFieldsWritten = Map.empty,
        methodsCalled = methodsCalled,
        methodsCalledStatically = methodsCalledStatically,
        staticMethodsCalled = Map.empty,
        instantiatedClasses = instantiatedClasses,
        accessedModules = Nil,
        usedInstanceTests = Nil,
        accessedClassData = Nil,
        referencedClasses = Nil
    )
  }

}

object Analyzer {
  def computeReachability(config: CommonPhaseConfig,
      symbolRequirements: SymbolRequirement,
      allowAddingSyntheticMethods: Boolean,
      inputProvider: InputProvider)(implicit ec: ExecutionContext): Future[Analysis] = {
    val analyzer = new Analyzer(config, symbolRequirements,
        allowAddingSyntheticMethods, inputProvider, ec)
    analyzer.computeReachability().map(_ => analyzer)
  }

  trait InputProvider {
    def classesWithEntryPoints(): TraversableOnce[String]

    def loadInfo(encodedName: String)(implicit ec: ExecutionContext): Option[Future[Infos.ClassInfo]]
  }

  private class WorkQueue(ec: ExecutionContext) {
    private val queue = new ConcurrentLinkedQueue[() => Unit]()
    private val working = new AtomicBoolean(false)
    private val pending = new AtomicInteger(0)
    private val promise = Promise[Unit]

    def enqueue[T](fut: Future[T])(onSuccess: T => Unit): Unit = {
      val got = pending.incrementAndGet()
      assert(got > 0)

      fut.onComplete {
        case Success(r) =>
          queue.add(() => onSuccess(r))
          tryDoWork()

        case Failure(t) =>
          promise.tryFailure(t)
      } (ec)
    }

    def join(): Future[Unit] = {
      tryDoWork()
      promise.future
    }

    @tailrec
    private def tryDoWork(): Unit = {
      if (!working.getAndSet(true)) {
        while (!queue.isEmpty) {
          try {
            val work = queue.poll()
            work()
          } catch {
            case t: Throwable => promise.tryFailure(t)
          }

          pending.decrementAndGet()
        }

        if (pending.compareAndSet(0, -1)) {
          assert(queue.isEmpty)
          promise.trySuccess(())
        }

        working.set(false)

        /* Another thread might have inserted work in the meantime but not yet
         * seen that we released the lock. Try and work steal again if this
         * happens.
         */
        if (!queue.isEmpty) tryDoWork()
      }
    }
  }
}
