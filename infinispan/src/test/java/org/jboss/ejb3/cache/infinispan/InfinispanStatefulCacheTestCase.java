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
import org.infinispan.Cache;
import org.infinispan.lifecycle.ComponentStatus;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.cache.ClusteredStatefulCache;
import org.jboss.ejb3.cache.infinispan.CacheSource;
import org.jboss.ejb3.cache.infinispan.InfinispanStatefulCache;
import org.jboss.ejb3.stateful.StatefulBeanContext;
import org.jboss.ejb3.stateful.StatefulContainer;
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
   private IMocksControl control = EasyMock.createStrictControl();
   private CacheSource source = this.control.createMock(CacheSource.class);
   private CacheInvoker invoker = this.control.createMock(CacheInvoker.class);
   @SuppressWarnings("unchecked")
   private Cache<Object, StatefulBeanContext> cache = this.control.createMock(Cache.class);
   
   private StatefulContainer container;
   private StatefulBeanContext context;  
   
   private ClusteredStatefulCache statefulCache = new InfinispanStatefulCache(this.source, this.invoker, Executors.defaultThreadFactory());
   
   @SuppressWarnings("deprecation")
   @Before
   public void before() throws Exception
   {
      this.container = this.createContainer(TestBean.class);
      this.context = new InfinispanStatefulCache.InfinispanStatefulBeanContext(this.container, new TestBean());
      
      EasyMock.expect(this.source.<Object, StatefulBeanContext>getCache(EasyMock.same(this.container))).andReturn(this.cache);
      
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
