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

import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.ejb3.annotation.CacheConfig;
import org.jboss.ejb3.stateful.StatefulContainer;
import org.jboss.ha.ispn.CacheContainerRegistry;
import org.jboss.util.StringPropertyReplacer;

/**
 * @author Paul Ferraro
 *
 */
public class DefaultCacheSource implements CacheSource
{
   public static final String PREFIX = "infinispan.";
   public static final String MODE = "mode";
   public static final String OWNERS = "owners";
   
//   private static final Logger log = Logger.getLogger(DefaultCacheSource.class);
   
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
      String containerName = StringPropertyReplacer.replaceProperties(cacheConfig.name());
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
      
      Configuration configuration = container.defineConfiguration(cacheName, templateCacheName, new Configuration());
/*
      for (CacheProperty cacheProperty: cacheConfig.properties())
      {
         String name = cacheProperty.name();
         
         if (!name.startsWith(PREFIX))
         {
            log.debug(String.format("Ignoring cache property \"%s\" for bean %s", name, cacheName));
            continue;
         }

         String property = name.substring(PREFIX.length());
         String value = cacheProperty.value();
         
         if (property.equals(MODE))
         {
            try
            {
               configuration.setCacheMode(CacheMode.valueOf(value.toUpperCase(Locale.ENGLISH)));
            }
            catch (IllegalArgumentException e)
            {
               throw new IllegalArgumentException(String.format("\"%s\" is not a valid \"%s\" for bean %s  Valid modes: %s", value, name, cacheName, CacheMode.values()), e);
            }
         }
         else if (property.equals(OWNERS))
         {
            try
            {
               configuration.setNumOwners(Integer.valueOf(value));
            }
            catch (NumberFormatException e)
            {
               throw new IllegalArgumentException(String.format("\"%s\" is not a valid \"%s\" for bean %s  Integer expected.", value, name, cacheName), e);
            }
         }
         else
         {
            log.info(String.format("Ignoring unrecognized cache property \"%s\" for bean %s", name, value));
         }
      }
*/
      configuration.setEvictionMaxEntries(cacheConfig.maxSize());
      
      if (configuration.getCacheMode().isDistributed())
      {
         configuration.setL1CacheEnabled(true);
         configuration.setL1Lifespan(TimeUnit.SECONDS.toMillis(cacheConfig.idleTimeoutSeconds()));
      }
      
      return container.getCache(cacheName);
   }
}
