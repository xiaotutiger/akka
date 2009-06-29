/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.actor

import java.util.{List => JList, ArrayList}
import java.lang.reflect.{Method, Field}
import java.lang.annotation.Annotation

import kernel.config.ActiveObjectGuiceConfigurator
import kernel.config.ScalaConfig._
import kernel.camel.{MessageDriven, ActiveObjectProducer}
import kernel.nio.{RemoteRequest, NettyClient}
import kernel.stm.{TransactionManagement, TransactionAwareWrapperException, ChangeSet, Transaction}

import kernel.util.Helpers.ReadWriteLock
import kernel.util.{HashCode, ResultOrFailure}
import kernel.state.{Transactional, TransactionalMap, TransactionalRef, TransactionalVector}

import org.codehaus.aspectwerkz.intercept.{Advisable, AroundAdvice}
import org.codehaus.aspectwerkz.joinpoint.{MethodRtti, JoinPoint}
import org.codehaus.aspectwerkz.proxy.Proxy

import org.apache.camel.{Processor, Exchange}

import scala.collection.mutable.HashMap

sealed class ActiveObjectException(msg: String) extends RuntimeException(msg)
class ActiveObjectInvocationTimeoutException(msg: String) extends ActiveObjectException(msg)

object Annotations {
  import se.scalablesolutions.akka.annotation._
  val transactional = classOf[transactional]
  val oneway =        classOf[oneway]
  val immutable =     classOf[immutable]
  val state =         classOf[state]
}

/**
 * Factory for Java API.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveObjectFactory {
  def newInstance[T](target: Class[T]): T = ActiveObject.newInstance(target, new Dispatcher(target.getName), false)

  def newInstance[T](intf: Class[T], target: AnyRef): T = ActiveObject.newInstance(intf, target, new Dispatcher(intf.getName), false)

  def newRemoteInstance[T](target: Class[T]): T = ActiveObject.newInstance(target, new Dispatcher(target.getName), true)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef): T = ActiveObject.newInstance(intf, target, new Dispatcher(intf.getName), true)

  /*
  def newInstanceAndLink[T](target: Class[T], supervisor: AnyRef): T = {
    val actor = new Dispatcher(target.getName)
    ActiveObject.newInstance(target, actor)
  }

  def newInstanceAndLink[T](intf: Class[T], target: AnyRef, supervisor: AnyRef): T = {
    val actor = new Dispatcher(target.getName)
    ActiveObject.newInstance(intf, target, actor)
  }
  */
  // ================================================

  private[kernel] def newInstance[T](target: Class[T], actor: Actor, remote: Boolean): T = {
    ActiveObject.newInstance(target, actor, remote)
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor, remote: Boolean): T = {
    ActiveObject.newInstance(intf, target, actor, remote)
  }

  private[kernel] def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor =
    ActiveObject.supervise(restartStrategy, components)
}

/**
 * Factory for Scala API.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ActiveObject {
  val MATCH_ALL = "execution(* *.*(..))"
  val AKKA_CAMEL_ROUTING_SCHEME = "akka"

  def newInstance[T](target: Class[T]): T = newInstance(target, new Dispatcher(target.getName), false)

  def newInstance[T](intf: Class[T], target: AnyRef): T = newInstance(intf, target, new Dispatcher(intf.getName), false)

  def newRemoteInstance[T](target: Class[T]): T = newInstance(target, new Dispatcher(target.getName), true)

  def newRemoteInstance[T](intf: Class[T], target: AnyRef): T = newInstance(intf, target, new Dispatcher(intf.getName), true)

  // ================================================

  private[kernel] def newInstance[T](target: Class[T], actor: Actor, remote: Boolean): T = {
    if (remote) NettyClient.connect
    val proxy = Proxy.newInstance(target, false, true)
    // FIXME switch to weaving in the aspect at compile time
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      MATCH_ALL, new TransactionalAroundAdvice(target, proxy, actor, remote))
    proxy.asInstanceOf[T]
  }

  private[kernel] def newInstance[T](intf: Class[T], target: AnyRef, actor: Actor, remote: Boolean): T = {
    if (remote) NettyClient.connect
    val proxy = Proxy.newInstance(Array(intf), Array(target), false, true)
    proxy.asInstanceOf[Advisable].aw_addAdvice(
      MATCH_ALL, new TransactionalAroundAdvice(intf, target, actor, remote))
    proxy.asInstanceOf[T]
  }

  private[kernel] def supervise(restartStrategy: RestartStrategy, components: List[Worker]): Supervisor = {
    object factory extends SupervisorFactory {
      override def getSupervisorConfig = SupervisorConfig(restartStrategy, components)
    }
    val supervisor = factory.newSupervisor
    supervisor ! StartSupervisor
    supervisor
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable sealed class TransactionalAroundAdvice(
    val target: Class[_], val targetInstance: AnyRef, actor: Actor, val isRemote: Boolean)
    extends AroundAdvice {
  val id = target.getName
  actor.start
  
  import kernel.reactor._
  private[this] var dispatcher = new ProxyMessageDispatcher
  private[this] var mailbox = dispatcher.messageQueue
  dispatcher.start

  def invoke(joinpoint: JoinPoint): AnyRef = dispatch(joinpoint)

  private def dispatch(joinpoint: JoinPoint) = {
    if (isRemote) remoteDispatch(joinpoint)
    else localDispatch(joinpoint)
  }

  private def localDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    if (isOneWay(rtti)) actor !! Invocation(joinpoint) // FIXME investigate why ! causes TX to race
    else {
      val result = actor !! Invocation(joinpoint)
      if (result.isDefined) result.get
      else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
    }
  }

  private def remoteDispatch(joinpoint: JoinPoint): AnyRef = {
    val rtti = joinpoint.getRtti.asInstanceOf[MethodRtti]
    val oneWay = isOneWay(rtti)
    val future = NettyClient.send(
      new RemoteRequest(false, rtti.getParameterValues, rtti.getMethod.getName, target.getName, None, oneWay, false))
    if (oneWay) null // for void methods
    else {
      future.await
      val result = getResultOrThrowException(future)
      if (result.isDefined) result.get
      else throw new IllegalStateException("No result defined for invocation [" + joinpoint + "]")
    }
  }

  private def getResultOrThrowException[T](future: FutureResult): Option[T] =
    if (future.exception.isDefined) {
      val (_, cause) = future.exception.get
      throw cause
    } else future.result.asInstanceOf[Option[T]]
  
  private def isOneWay(rtti: MethodRtti) = rtti.getMethod.getReturnType == java.lang.Void.TYPE
}

/**
 * Represents a snapshot of the current invocation.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable private[kernel] case class Invocation(val joinpoint: JoinPoint) {

  override def toString: String = synchronized {
    "Invocation [joinpoint: " + joinpoint.toString + "]"
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, joinpoint)
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[Invocation] &&
    that.asInstanceOf[Invocation].joinpoint == joinpoint
  }
}

/**
 * Generic Actor managing Invocation dispatch, transaction and error management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] class Dispatcher(val targetName: String) extends Actor {
  makeTransactional

  // FIXME implement the pre/post restart methods and call annotated methods on the POJO
  
  override def receive: PartialFunction[Any, Unit] = {
    case Invocation(joinpoint: JoinPoint) =>
       reply(joinpoint.proceed)
    case unexpected =>
      throw new ActiveObjectException("Unexpected message [" + unexpected + "] sent to [" + this + "]")
  }
}

/*
ublic class CamelInvocationHandler implements InvocationHandler {
     private final Endpoint endpoint;
    private final Producer producer;
    private final MethodInfoCache methodInfoCache;

    public CamelInvocationHandler(Endpoint endpoint, Producer producer, MethodInfoCache methodInfoCache) {
        this.endpoint = endpoint;
        this.producer = producer;
        this.methodInfoCache = methodInfoCache;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        BeanInvocation invocation = new BeanInvocation(method, args);
        ExchangePattern pattern = ExchangePattern.InOut;
        MethodInfo methodInfo = methodInfoCache.getMethodInfo(method);
        if (methodInfo != null) {
            pattern = methodInfo.getPattern();
        }
        Exchange exchange = new DefaultExchange(endpoint, pattern);
        exchange.getIn().setBody(invocation);

        producer.process(exchange);
        Throwable fault = exchange.getException();
        if (fault != null) {
            throw new InvocationTargetException(fault);
        }
        if (pattern.isOutCapable()) {
            return exchange.getOut().getBody();
        } else {
            return null;
        }
    }
}

      if (joinpoint.target.isInstanceOf[MessageDriven] &&
          joinpoint.method.getName == "onMessage") {
        val m = joinpoint.method

      val endpointName = m.getDeclaringClass.getName + "." + m.getName
        val activeObjectName = m.getDeclaringClass.getName
        val endpoint = conf.getRoutingEndpoint(conf.lookupUriFor(m))
        val producer = endpoint.createProducer
        val exchange = endpoint.createExchange
        exchange.getIn().setBody(joinpoint)
        producer.process(exchange)
        val fault = exchange.getException();
        if (fault != null) throw new InvocationTargetException(fault)

        // FIXME: need some timeout and future here...
        exchange.getOut.getBody

      } else
*/
