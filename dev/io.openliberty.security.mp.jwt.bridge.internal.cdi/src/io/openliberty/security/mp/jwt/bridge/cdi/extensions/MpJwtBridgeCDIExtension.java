/*******************************************************************************
 * Copyright (c) 2024 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.security.mp.jwt.bridge.cdi.extensions;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.security.javaeesec.cdi.extensions.HttpAuthenticationMechanismsTracker;
import com.ibm.ws.security.javaeesec.cdi.extensions.PrimarySecurityCDIExtension;

import io.openliberty.security.jakartasec.JakartaSec30Constants;
import io.openliberty.security.mp.jwt.bridge.cdi.beans.JwtHttpAuthenticationMechanism;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.WithAnnotations;

/**
 * CDI Extension to process the {@link JwtAuthenticationMechanismDefinition} annotation
 * and register beans required for Jakarta Security 3.0.
 */
@Component(service = {},
           immediate = true,
           configurationPolicy = ConfigurationPolicy.IGNORE,
           property = "service.vendor=IBM")
public class MpJwtBridgeCDIExtension implements Extension {

    private static final TraceComponent tc = Tr.register(MpJwtBridgeCDIExtension.class);

    private static PrimarySecurityCDIExtension primarySecurityCDIExtension;

    private final Set<Bean> beansToAdd = new HashSet<Bean>();
    private final String applicationName;

    public MpJwtBridgeCDIExtension() {
        applicationName = HttpAuthenticationMechanismsTracker.getApplicationName();
    }

    @SuppressWarnings("static-access")
    @Reference
    protected void setPrimarySecurityCDIExtension(PrimarySecurityCDIExtension primarySecurityCDIExtension) {
        this.primarySecurityCDIExtension = primarySecurityCDIExtension;
        primarySecurityCDIExtension.registerMechanismClass(JwtHttpAuthenticationMechanism.class);
    }

    @Deactivate
    protected void deactivate() {
        primarySecurityCDIExtension.deregisterMechanismClass(JwtHttpAuthenticationMechanism.class);
    }

    public <T> void processAnnotatedOidc(@WithAnnotations({ JwtAuthenticationMechanismDefinition.class }) @Observes ProcessAnnotatedType<T> event, BeanManager beanManager) {
        AnnotatedType<T> annotatedType = event.getAnnotatedType();
        Annotation jwtAnnotation = annotatedType.getAnnotation(JwtAuthenticationMechanismDefinition.class);
        Class<?> annotatedClass = annotatedType.getJavaClass();
        addJwtHttpAuthenticationMechanismBean(jwtAnnotation, annotatedClass, annotatedType);
    }

    private <T> void addJwtHttpAuthenticationMechanismBean(Annotation annotation, Class<?> annotatedClass, AnnotatedType<T> annotatedType) {
        Properties props = new Properties();
        props.put(JakartaSec30Constants.OIDC_ANNOTATION, new JwtAuthenticationMechanismDefinitionHolder((JwtAuthenticationMechanismDefinition) annotation));
        Set<Annotation> annotations = annotatedType.getAnnotations();
        primarySecurityCDIExtension.addAuthMech(applicationName, annotatedClass, JwtAuthenticationMechanismDefinition.class, annotations, props);
    }

    public <T> void afterBeanDiscovery(@Observes AfterBeanDiscovery afterBeanDiscovery, BeanManager beanManager) {
        if (tc.isDebugEnabled()) {
            Tr.debug(tc, "afterBeanDiscovery : instance : " + Integer.toHexString(this.hashCode()) + " BeanManager : " + Integer.toHexString(beanManager.hashCode()));
        }

        // Verification of mechanisms and registration of ModulePropertiesProviderBean performed in JavaEESecCDIExtension's afterBeanDiscovery()
        for (Bean bean : beansToAdd) {
            afterBeanDiscovery.addBean(bean);
        }
    }
}
