/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.data.internal.cdi;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.Producer;
import jakarta.enterprise.inject.spi.ProducerFactory;

import io.openliberty.data.Data;
import io.openliberty.data.Entity;

public class BeanProducerFactory<R> implements ProducerFactory<R> {
    private final Entity entity;

    BeanProducerFactory(Entity entity) {
        this.entity = entity;
    }

    @Override
    public <T> Producer<T> createProducer(Bean<T> bean) {
        Data data = bean.getBeanClass().getAnnotation(Data.class);
        if (data == null) {
            System.out.println("createProducer null because " + bean + " has no @Data");
            return null;
        } else {
            return new BeanProducer<T>(bean, entity);
        }
    }
}