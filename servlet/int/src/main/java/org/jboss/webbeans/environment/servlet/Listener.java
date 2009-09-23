/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.webbeans.environment.servlet;

import javax.el.ELContextListener;
import javax.enterprise.inject.spi.BeanManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.jsp.JspApplicationContext;
import javax.servlet.jsp.JspFactory;

import org.jboss.webbeans.bootstrap.api.Bootstrap;
import org.jboss.webbeans.bootstrap.api.Environments;
import org.jboss.webbeans.context.api.BeanStore;
import org.jboss.webbeans.context.api.helpers.ConcurrentHashMapBeanStore;
import org.jboss.webbeans.environment.servlet.deployment.ServletDeployment;
import org.jboss.webbeans.environment.servlet.services.ServletResourceInjectionServices;
import org.jboss.webbeans.environment.servlet.services.ServletServicesImpl;
import org.jboss.webbeans.environment.servlet.util.Reflections;
import org.jboss.webbeans.environment.tomcat.WebBeansAnnotationProcessor;
import org.jboss.webbeans.injection.spi.ResourceInjectionServices;
import org.jboss.webbeans.log.Log;
import org.jboss.webbeans.log.Logging;
import org.jboss.webbeans.manager.api.WebBeansManager;
import org.jboss.webbeans.servlet.api.ServletListener;
import org.jboss.webbeans.servlet.api.ServletServices;
import org.jboss.webbeans.servlet.api.helpers.ForwardingServletListener;

/**
 * @author Pete Muir
 */
public class Listener extends ForwardingServletListener
{
   
   private static final Log log = Logging.getLog(Listener.class);
   
   private static final String BOOTSTRAP_IMPL_CLASS_NAME = "org.jboss.webbeans.bootstrap.WebBeansBootstrap";
   private static final String WEB_BEANS_LISTENER_CLASS_NAME = "org.jboss.webbeans.servlet.WebBeansListener";
   private static final String APPLICATION_BEAN_STORE_ATTRIBUTE_NAME = Listener.class.getName() + ".applicationBeanStore";
   private static final String EXPRESSION_FACTORY_NAME = "org.jboss.webbeans.el.ExpressionFactory";
   
   private final transient Bootstrap bootstrap;
   private final transient ServletListener webBeansListener;
   private WebBeansManager manager;
   
   public Listener() 
   {
      try
      {
         bootstrap = Reflections.newInstance(BOOTSTRAP_IMPL_CLASS_NAME);
      }
      catch (IllegalArgumentException e)
      {
         throw new IllegalStateException("Error loading Web Beans bootstrap, check that Web Beans is on the classpath", e);
      }
      try
      {
         webBeansListener = Reflections.newInstance(WEB_BEANS_LISTENER_CLASS_NAME);
      }
      catch (IllegalArgumentException e)
      {
         throw new IllegalStateException("Error loading Web Beans listener, check that Web Beans is on the classpath", e);
      }
   }

   @Override
   public void contextDestroyed(ServletContextEvent sce)
   {
      bootstrap.shutdown();
      super.contextDestroyed(sce);
   }

   @Override
   public void contextInitialized(ServletContextEvent sce)
   {
      BeanStore applicationBeanStore = new ConcurrentHashMapBeanStore();
      sce.getServletContext().setAttribute(APPLICATION_BEAN_STORE_ATTRIBUTE_NAME, applicationBeanStore);
      
      
      
      ServletDeployment deployment = new ServletDeployment(sce.getServletContext());
      try
      {
    	  deployment.getWebAppBeanDeploymentArchive().getServices().add(ResourceInjectionServices.class, new ServletResourceInjectionServices() {});
      }
      catch (NoClassDefFoundError e)
      {
    	 // Support GAE 
    	 log.warn("@Resource injection not available in simple beans");
      }
      
      deployment.getServices().add(ServletServices.class, new ServletServicesImpl(deployment.getWebAppBeanDeploymentArchive()));
      
      bootstrap.startContainer(Environments.SERVLET, deployment, applicationBeanStore).startInitialization();
      manager = bootstrap.getManager(deployment.getWebAppBeanDeploymentArchive());
      
      boolean tomcat = true;
      try
      {
         Reflections.classForName("org.apache.AnnotationProcessor");
      }
      catch (IllegalArgumentException e) 
      {
         log.info("JSR-299 injection will not be available in Servlets, Filters etc. This facility is only available in Tomcat");
         tomcat = false;
      }
      
      if (tomcat)
      {
         // Try pushing a Tomcat AnnotationProcessor into the servlet context
         try
         {
            Class<?> clazz = Reflections.classForName(WebBeansAnnotationProcessor.class.getName());
            Object annotationProcessor = clazz.getConstructor(WebBeansManager.class).newInstance(manager);
            sce.getServletContext().setAttribute(WebBeansAnnotationProcessor.class.getName(), annotationProcessor);
         }
         catch (Exception e) 
         {
            log.error("Unable to create Tomcat AnnotationProcessor. JSR-299 injection will not be available in Servlets, Filters etc.", e);
         }
      }

      // Push the manager into the servlet context so we can access in JSF
      sce.getServletContext().setAttribute(BeanManager.class.getName(), manager);
      
      if (JspFactory.getDefaultFactory() != null)
      {
         JspApplicationContext jspApplicationContext = JspFactory.getDefaultFactory().getJspApplicationContext(sce.getServletContext());
         
         // Register the ELResolver with JSP
         jspApplicationContext.addELResolver(manager.getELResolver());
         
         // Register ELContextListener with JSP
         jspApplicationContext.addELContextListener(Reflections.<ELContextListener>newInstance("org.jboss.webbeans.el.WebBeansELContextListener"));
         
         // Push the wrapped expression factory into the servlet context so that Tomcat or Jetty can hook it in using a container code
         sce.getServletContext().setAttribute(EXPRESSION_FACTORY_NAME, manager.wrapExpressionFactory(jspApplicationContext.getExpressionFactory()));
      }
      
      bootstrap.deployBeans().validateBeans().endInitialization();
      super.contextInitialized(sce);
   }
   
   

   @Override
   protected ServletListener delegate()
   {
      return webBeansListener;
   }
   
}
