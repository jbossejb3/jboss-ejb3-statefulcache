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

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.jboss.ejb3.cache.Ejb3CacheFactory;
import org.jboss.ha.ispn.DefaultCacheContainerRegistry;
import org.jboss.ha.ispn.invoker.CacheInvoker;
import org.jboss.ha.ispn.invoker.RetryingCacheInvoker;

/**
 * @author Paul Ferraro
 */
public class InfinispanStatefulCacheFactory implements Ejb3CacheFactory
{
   private CacheSource cacheSource = new DefaultCacheSource(DefaultCacheContainerRegistry.getInstance());
   private LockManagerSource lockManagerSource = new DefaultLockManagerSource();
   private ThreadFactory threadFactory = Executors.defaultThreadFactory();
   private CacheInvoker invoker = new RetryingCacheInvoker(0, 0);

   /**
    * {@inheritDoc}
    * @see org.jboss.ejb3.cache.Ejb3CacheFactory#createCache()
    */
   @Override
   @SuppressWarnings("deprecation")
   public org.jboss.ejb3.cache.StatefulCache createCache()
   {
      return new InfinispanStatefulCache(this.cacheSource, this.lockManagerSource, this.invoker, this.threadFactory);
   }
   
   public void setLockManagerSource(LockManagerSource source)
   {
      this.lockManagerSource = source;
   }
   
   public void setCacheSource(CacheSource source)
   {
      this.cacheSource = source;
   }
   
   public void setThreadFactory(ThreadFactory threadFactory)
   {
      this.threadFactory = threadFactory;
   }
   
   public void setCacheInvoker(CacheInvoker invoker)
   {
      this.invoker = invoker;
   }
}
