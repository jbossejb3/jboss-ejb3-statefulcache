/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.ejb3.cache.infinispan;

import java.util.List;
import java.util.concurrent.Executors;

import javax.ejb.Stateful;

import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.easymock.IMocksControl;
import org.infinispan.AdvancedCache;
import org.infinispan.context.Flag;
import org.infinispan.lifecycle.ComponentStatus;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.cache.ClusteredStatefulCache;
import org.jboss.ejb3.stateful.StatefulBeanContext;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ha.framework.interfaces.GroupMembershipNotifier;
import org.jboss.ha.framework.interfaces.GroupRpcDispatcher;
import org.jboss.ha.framework.server.lock.SharedLocalYieldingClusterLockManager;
import org.jboss.ha.ispn.invoker.CacheInvoker;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Paul Ferraro
 *
 */
public class InfinispanStatefulCacheTestCase extends StatefulContainerFactory
{
   private static final String SERVICE_NAME = "test-service";
   
   private IMocksControl control = EasyMock.createStrictControl();
   private CacheSource cacheSource = this.control.createMock(CacheSource.class);
   private LockManagerSource lockManagerSource = this.control.createMock(LockManagerSource.class);
   private CacheInvoker invoker = this.control.createMock(CacheInvoker.class);
   @SuppressWarnings("unchecked")
   private AdvancedCache<Object, StatefulBeanContext> cache = this.control.createMock(AdvancedCache.class);
   private GroupRpcDispatcher rpcDispatcher = this.control.createMock(GroupRpcDispatcher.class);
   private GroupMembershipNotifier membershipNotifier = this.control.createMock(GroupMembershipNotifier.class);

   private SharedLocalYieldingClusterLockManager lockManager;
   private StatefulContainer container;
   private StatefulBeanContext context;  
   
   private ClusteredStatefulCache statefulCache = new InfinispanStatefulCache(this.cacheSource, this.lockManagerSource, this.invoker, Executors.defaultThreadFactory());
   
   @SuppressWarnings("deprecation")
   @Before
   public void before() throws Exception
   {
//      EasyMock.expect(this.rpcDispatcher.isConsistentWith(EasyMock.same(this.membershipNotifier))).andReturn(true);
      
//      this.control.replay();
      
//      this.lockManager = new SharedLocalYieldingClusterLockManager(SERVICE_NAME, this.rpcDispatcher, this.membershipNotifier);

//      this.control.verify();
//      this.control.reset();
      
      this.container = this.createContainer(TestBean.class);
      this.context = new InfinispanStatefulCache.InfinispanStatefulBeanContext(this.container, new TestBean());
      
      EasyMock.expect(this.cacheSource.<Object, StatefulBeanContext>getCache(EasyMock.same(this.container))).andReturn(this.cache);
      EasyMock.expect(this.lockManagerSource.getLockManager(EasyMock.same(this.cache))).andReturn(null);
      
      this.control.replay();
      
      this.statefulCache.initialize(this.container);
      
      this.control.verify();
      this.control.reset();

      EasyMock.expect(this.cache.getStatus()).andReturn(ComponentStatus.TERMINATED);
      this.cache.start();
      this.cache.addListener(EasyMock.same(this.statefulCache));

      this.control.replay();
      
      this.statefulCache.start();
      
      this.control.verify();
      this.control.reset();
   }

   @After
   public void after()
   {
      this.control.reset();
      
      this.cache.removeListener(EasyMock.same(this.statefulCache));
      EasyMock.expect(this.cache.getAdvancedCache()).andReturn(this.cache);
      EasyMock.expect(this.cache.withFlags(Flag.CACHE_MODE_LOCAL)).andReturn(this.cache);
      this.cache.clear();
      this.cache.stop();
      
      this.control.replay();
      
      this.statefulCache.stop();
      
      this.control.verify();
      this.control.reset();
   }

   @Test
   public void peek() throws Exception
   {
      Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>> capturedOperation = new Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>>();
      
      EasyMock.expect(this.invoker.invoke(EasyMock.same(this.cache), EasyMock.capture(capturedOperation))).andReturn(this.context);
      
      this.control.replay();
      
      StatefulBeanContext result = this.statefulCache.peek(this.context.getId());

      this.control.verify();
      
      Assert.assertSame(result, this.context);
      
      this.control.reset();
      
      
      InfinispanStatefulCache.Operation<StatefulBeanContext> operation = capturedOperation.getValue();

      EasyMock.expect(this.cache.get(this.context.getId())).andReturn(this.context);

      this.control.replay();
      
      result = operation.invoke(this.cache);

      this.control.verify();
      
      Assert.assertSame(result, this.context);

      this.control.reset();
   }

   @Test
   public void release() throws Exception
   {
      this.control.replay();
      
      this.statefulCache.release(this.context);

      this.control.verify();
      
      Assert.assertFalse(this.context.isInUse());
      
      this.control.reset();
   }

   @Test
   public void replicate() throws Exception
   {
      Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>> capturedOperation = new Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>>();
      
      EasyMock.expect(this.invoker.invoke(EasyMock.same(this.cache), EasyMock.capture(capturedOperation))).andReturn(this.context);
      
      this.control.replay();
      
      this.statefulCache.replicate(this.context);

      this.control.verify();
      this.control.reset();
      
      
      InfinispanStatefulCache.Operation<StatefulBeanContext> operation = capturedOperation.getValue();

      EasyMock.expect(this.cache.put(context.getId(), context)).andReturn(null);

      this.control.replay();
      
      StatefulBeanContext result = operation.invoke(this.cache);

      this.control.verify();
      
      Assert.assertNull(result);
      
      this.control.reset();
   }

   @Test
   public void remove() throws Exception
   {
      Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>> capturedOperation = new Capture<InfinispanStatefulCache.Operation<StatefulBeanContext>>(CaptureType.ALL);
      
      EasyMock.expect(this.invoker.invoke(EasyMock.same(this.cache), EasyMock.capture(capturedOperation))).andReturn(this.context);
      EasyMock.expect(this.invoker.invoke(EasyMock.same(this.cache), EasyMock.capture(capturedOperation))).andReturn(this.context);
      
      this.control.replay();
      
      this.statefulCache.remove(context.getId());

      this.control.verify();

      List<InfinispanStatefulCache.Operation<StatefulBeanContext>> operations = capturedOperation.getValues();
      Assert.assertEquals(2, operations.size());

      this.control.reset();
      
      
      EasyMock.expect(this.cache.get(this.context.getId())).andReturn(this.context);

      this.control.replay();
      
      StatefulBeanContext result = operations.get(0).invoke(this.cache);

      this.control.verify();
      
      Assert.assertSame(result, this.context);

      this.control.reset();

      
      EasyMock.expect(this.cache.remove(this.context.getId())).andReturn(null);

      this.control.replay();
      
      result = operations.get(1).invoke(this.cache);

      this.control.verify();
      
      Assert.assertNull(result);

      this.control.reset();
   }
   
   @Stateful
   @CacheConfig
   public static class TestBean
   {
   }
}
