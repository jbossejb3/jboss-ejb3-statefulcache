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

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.config.Configuration.CacheMode;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ha.ispn.CacheContainerRegistry;

/**
 * @author Paul Ferraro
 *
 */
public class DefaultCacheSource implements CacheSource
{
   private final CacheContainerRegistry registry;
   
   public DefaultCacheSource(CacheContainerRegistry registry)
   {
      this.registry = registry;
   }
   
   /**
    * {@inheritDoc}
    * @see org.jboss.ejb3.cache.infinispan.CacheSource#getCache(org.jboss.ejb3.stateful.StatefulContainer)
    */
   @Override
   public <K, V> Cache<K, V> getCache(StatefulContainer ejbContainer)
   {
      CacheConfig cacheConfig = ejbContainer.getAnnotation(CacheConfig.class);
      String containerName = cacheConfig.name();
      String templateCacheName = null;

      if ((containerName == null) || containerName.isEmpty())
      {
         containerName = CacheConfig.DEFAULT_CLUSTERED_OBJECT_NAME;
      }
      else
      {
         String[] parts = containerName.split("/");
         
         if (parts.length == 2)
         {
            containerName = parts[0];
            templateCacheName = parts[1];
         }
      }
      
      String cacheName = ejbContainer.getDeploymentPropertyListString();
      
      EmbeddedCacheManager container = this.registry.getCacheContainer(containerName);
      
      this.applyOverrides(container.defineConfiguration(cacheName, templateCacheName, new Configuration()), cacheConfig);
      
      return container.getCache(cacheName);
   }

   private void applyOverrides(Configuration configuration, CacheConfig cacheConfig)
   {
      int backups = cacheConfig.backups();
      CacheConfig.Mode mode = cacheConfig.mode();
      
      CacheMode cacheMode = configuration.getCacheMode();
      
      if (backups != CacheConfig.DEFAULT_BACKUPS)
      {
         configuration.setNumOwners(backups);
         
         if (backups == CacheConfig.NO_BACKUPS)
         {
            cacheMode = CacheMode.LOCAL;
         }
         else
         {
            boolean synchronous = cacheMode.isSynchronous();
            if (backups > 0)
            {
               cacheMode = synchronous ? CacheMode.DIST_SYNC : CacheMode.DIST_ASYNC;
            }
            else // Negative backups means total replication
            {
               cacheMode = synchronous ? CacheMode.REPL_SYNC : CacheMode.REPL_ASYNC;
            }
         }
      }
      
      if (mode != CacheConfig.Mode.DEFAULT)
      {
         switch (mode)
         {
            case SYNCHRONOUS:
            {
               cacheMode = cacheMode.toSync();
               break;
            }
            case ASYNCHRONOUS:
            {
               cacheMode = cacheMode.toAsync();
               break;
            }
         }
      }
      
      configuration.setCacheMode(cacheMode);
   }
}
