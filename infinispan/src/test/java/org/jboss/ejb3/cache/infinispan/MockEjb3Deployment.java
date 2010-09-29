/*
 * JBoss, Home of Professional Open Source
 * Copyright 2007, Red Hat Middleware LLC, and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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

import java.util.HashMap;
import java.util.concurrent.Executors;

import javax.security.jacc.PolicyConfiguration;

import org.jboss.deployers.structure.spi.helpers.AbstractDeploymentContext;
import org.jboss.deployers.structure.spi.helpers.AbstractDeploymentUnit;
import org.jboss.ejb3.DependencyPolicy;
import org.jboss.ejb3.DeploymentScope;
import org.jboss.ejb3.DeploymentUnit;
import org.jboss.ejb3.Ejb3Deployment;
import org.jboss.ejb3.async.spi.AttachmentNames;
import org.jboss.ejb3.cache.CacheFactoryRegistry;
import org.jboss.ejb3.cache.Ejb3CacheFactory;
import org.jboss.ejb3.cache.NoPassivationCacheFactory;
import org.jboss.ejb3.cache.simple.SimpleStatefulCacheFactory;
import org.jboss.ejb3.cache.tree.StatefulTreeCacheFactory;
import org.jboss.ejb3.common.resolvers.plugins.FirstMatchEjbReferenceResolver;
import org.jboss.ejb3.common.resolvers.spi.EjbReferenceResolver;
import org.jboss.ejb3.deployers.JBoss5DependencyPolicy;
import org.jboss.ejb3.instantiator.impl.Ejb31SpecBeanInstantiator;
import org.jboss.ejb3.javaee.JavaEEComponent;
import org.jboss.ejb3.pool.PoolFactory;
import org.jboss.ejb3.pool.PoolFactoryRegistry;
import org.jboss.ejb3.pool.StrictMaxPoolFactory;
import org.jboss.ejb3.pool.ThreadlocalPoolFactory;

/**
 * Comment
 *
 * @author <a href="mailto:carlo.dewolf@jboss.com">Carlo de Wolf</a>
 * @version $Revision: $
 */
public class MockEjb3Deployment extends Ejb3Deployment
{
   public static MockEjb3Deployment create(DeploymentUnit unit)
   {
      unit.addAttachment(AttachmentNames.ASYNC_INVOCATION_PROCESSOR, Executors.newCachedThreadPool());
      unit.addAttachment(org.jboss.ejb3.instantiator.spi.AttachmentNames.NAME_BEAN_INSTANCE_INSTANTIATOR,
            new Ejb31SpecBeanInstantiator());
      return new MockEjb3Deployment(unit);
   }

   public static MockEjb3Deployment create(DeploymentUnit unit, org.jboss.deployers.structure.spi.DeploymentUnit du)
   {
      unit.addAttachment(AttachmentNames.ASYNC_INVOCATION_PROCESSOR, Executors.newCachedThreadPool());
      unit.addAttachment(org.jboss.ejb3.instantiator.spi.AttachmentNames.NAME_BEAN_INSTANCE_INSTANTIATOR,
            new Ejb31SpecBeanInstantiator());
      return new MockEjb3Deployment(unit, du);
   }
   
   private MockEjb3Deployment(DeploymentUnit unit)
   {
      // TODO This should be replaced w/ a MockDeploymentUnit when completed, 
      // to support nested deployments, @see ejb3-test MockDeploymentUnit
      this(unit, new AbstractDeploymentUnit(new AbstractDeploymentContext("test", "")));
   }

   private MockEjb3Deployment(DeploymentUnit unit, org.jboss.deployers.structure.spi.DeploymentUnit du)
   {
      this(unit, du, null);
   }

   private MockEjb3Deployment(DeploymentUnit unit, org.jboss.deployers.structure.spi.DeploymentUnit du,
         DeploymentScope scope)
   {
      super(du, unit, scope, null);

      // Replace the scope if we haven't been given one (hacky, but there's
      // a chicken/egg thing here as MockDeploymentScope requires the instance
      // currently under construction, and DeploymentScope is @Deprecated anyway
      // in favor of a pluggable resolver architecture, so this is a stop-gap
      if (scope == null)
      {
         EjbReferenceResolver resolver = new FirstMatchEjbReferenceResolver();
         this.deploymentScope = new MockDeploymentScope(this, du, resolver);
      }

      PoolFactoryRegistry poolRegistry = new PoolFactoryRegistry();
      HashMap<String, Class<? extends PoolFactory>> poolFactories = new HashMap<String, Class<? extends PoolFactory>>();
      poolFactories.put("ThreadlocalPool", ThreadlocalPoolFactory.class);
      poolFactories.put("StrictMaxPool", StrictMaxPoolFactory.class);
      poolRegistry.setFactories(poolFactories);
      setPoolFactoryRegistry(poolRegistry);
      CacheFactoryRegistry cacheRegistry = new CacheFactoryRegistry();
      HashMap<String, Class<? extends Ejb3CacheFactory>> cacheFactories = new HashMap<String, Class<? extends Ejb3CacheFactory>>();
      cacheFactories.put("NoPassivationCache", NoPassivationCacheFactory.class);
      cacheFactories.put("SimpleStatefulCache", SimpleStatefulCacheFactory.class);
      cacheFactories.put("StatefulTreeCache", StatefulTreeCacheFactory.class);
      cacheRegistry.setFactories(cacheFactories);
      setCacheFactoryRegistry(cacheRegistry);

   }

   @Override
   public DependencyPolicy createDependencyPolicy(JavaEEComponent component)
   {
      return new JBoss5DependencyPolicy(component);
   }

   @Override
   protected PolicyConfiguration createPolicyConfiguration() throws Exception
   {
      throw new RuntimeException("mock");
   }

   @Override
   protected void putJaccInService(PolicyConfiguration pc, DeploymentUnit unit)
   {
      throw new RuntimeException("mock");
   }

}
