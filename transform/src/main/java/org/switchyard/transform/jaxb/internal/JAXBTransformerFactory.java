/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.switchyard.transform.jaxb.internal;

import org.apache.log4j.Logger;
import org.switchyard.common.type.Classes;
import org.switchyard.common.xml.QNameUtil;
import org.switchyard.exception.SwitchYardException;
import org.switchyard.metadata.java.JavaService;
import org.switchyard.transform.Transformer;
import org.switchyard.transform.TransformerFactory;
import org.switchyard.transform.config.model.JAXBTransformModel;
import org.switchyard.transform.config.model.JavaTransformType;

import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlType;
import javax.xml.namespace.QName;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JAXB Transformer factory.
 *
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class JAXBTransformerFactory implements TransformerFactory<JAXBTransformModel> {

    /**
     * Logger.
     */
    private static Logger _log = Logger.getLogger(JAXBTransformerFactory.class);

    @Override
    public Transformer<?, ?> newTransformer(JAXBTransformModel model) {
        QName fromType = model.getFrom();
        QName toType = model.getTo();

        if (toJavaTransformType(fromType, toType) == JavaTransformType.JAVA2XML) {
            return new JAXBMarshalTransformer(fromType, toType, model.getContextPath());
        } else {
            return new JAXBUnmarshalTransformer(fromType, toType, model.getContextPath());
        }
    }

    /**
     * Factory method for auto-adding JAXB Transformers for a Service interface.
     * @param serviceClass The Service class.
     * @return The list of JAX Transformers to be added for the supplied service class.
     * @throws SwitchYardException Unsupported JAXB type defined on interface.
     */
    public static List<Transformer<?, ?>> newTransformers(Class<?> serviceClass) throws SwitchYardException {
        List<Transformer<?, ?>> transformers = new ArrayList<Transformer<?, ?>>();
        Set<Class<?>> inputTypeSet = new HashSet<Class<?>>();
        Set<Class<?>> outputTypeSet = new HashSet<Class<?>>();

        if (serviceClass.isInterface()) {
            Method[] serviceOperations = serviceClass.getMethods();

            for (Method serviceOperation : serviceOperations) {
                Class<?>[] inTypes = serviceOperation.getParameterTypes();
                Class<?> outType = serviceOperation.getReturnType();

                if (inTypes.length == 1) {
                    inputTypeSet.add(inTypes[0]);
                }
                if (outType != null && !Void.TYPE.isAssignableFrom(outType)) {
                    outputTypeSet.add(outType);
                }
            }
        }

        // Add input and output transformers...
        for (Class<?> inputType : inputTypeSet) {
            addJAXBUnmarshalTransformer(inputType, transformers);
        }
        for (Class<?> outputType : outputTypeSet) {
            addJAXBMarshalTransformer(outputType, transformers);
        }

        return transformers;
    }

    private static void addJAXBUnmarshalTransformer(Class<?> inType, List<Transformer<?, ?>> transformers) throws SwitchYardException {
        Class<?> objectFactory = getObjectFactory(inType);

        if (objectFactory != null) {
            QName fromType = getTypeXMLQName(inType, objectFactory);

            if (fromType != null) {
                QName toType = JavaService.toMessageType(inType);
                transformers.add(new JAXBUnmarshalTransformer(fromType, toType, inType.getPackage().getName()));
            } else if (_log.isDebugEnabled()) {
                _log.debug(createMissingFactoryMethodMessage(inType, objectFactory));
            }
        }
    }

    private static void addJAXBMarshalTransformer(Class<?> outType, List<Transformer<?, ?>> transformers) throws SwitchYardException {
        Class<?> objectFactory = getObjectFactory(outType);

        if (objectFactory != null) {
            QName toType = getTypeXMLQName(outType, objectFactory);

            if (toType != null) {
                QName fromType = JavaService.toMessageType(outType);
                transformers.add(new JAXBMarshalTransformer(fromType, toType, outType.getPackage().getName()));
            } else if (_log.isDebugEnabled()) {
                _log.debug(createMissingFactoryMethodMessage(outType, objectFactory));
            }
        }
    }

    private static QName getTypeXMLQName(Class<?> type, Class<?> objectFactory) throws SwitchYardException {
        Method[] factoryMethods = objectFactory.getDeclaredMethods();

        for (Method factoryMethod : factoryMethods) {
            XmlElementDecl xmlElementDecl = factoryMethod.getAnnotation(XmlElementDecl.class);
            if (xmlElementDecl != null) {
                Class<?>[] factoryParams = factoryMethod.getParameterTypes();

                if (factoryParams.length == 1) {
                    QName qName = new QName(xmlElementDecl.namespace(), xmlElementDecl.name());
                    Class<?> factoryParam = factoryParams[0];

                    if (factoryParam == type) {
                        // This is the factory method for the specified type...
                        return qName;
                    }
                }
            }
        }

        return null;
    }

    private static Class getObjectFactory(Class<?> type) {
        if (type.getAnnotation(XmlType.class) != null) {
            // Get the ObjectFactory, if it exists...
            String objectFactoryName = type.getPackage().getName() + "." + "ObjectFactory";

            return Classes.forName(objectFactoryName, JAXBTransformerFactory.class);
        }

        return null;
    }

    protected static String createMissingFactoryMethodMessage(Class<?> type, Class<?> objectFactory) {
        StringBuilder messageBuilder = new StringBuilder();
        Method[] factoryMethods = objectFactory.getDeclaredMethods();

        messageBuilder.append("JAXB Type '" + type.getName() + "' does not have a JAXBElement factory method defined in " + objectFactory.getName()
                + ".  The supported JAXBElement factory methods are for types:");

        for (Method factoryMethod : factoryMethods) {
            XmlElementDecl xmlElementDecl = factoryMethod.getAnnotation(XmlElementDecl.class);
            if (xmlElementDecl != null) {
                Class<?>[] factoryParams = factoryMethod.getParameterTypes();

                if (factoryParams.length == 1) {
                    QName qName = new QName(xmlElementDecl.namespace(), xmlElementDecl.name());
                    Class<?> factoryParam = factoryParams[0];

                    messageBuilder.append("\n\t\t- Message QName: '" + qName.toString() + "'. Java Type: " + factoryParam.getName());
                }
            }
        }

        return messageBuilder.toString();
    }

    private static JavaTransformType toJavaTransformType(QName fromType, QName toType) {
        if (QNameUtil.isJavaMessageType(fromType)) {
            if (QNameUtil.isJavaMessageType(toType)) {
                throw new SwitchYardException("Invalid JAXB Transformer configuration.  The 'from' and 'to' transformation types are both Java types.  Exactly one must be a Java type.");
            }
            return JavaTransformType.JAVA2XML;
        } else if (QNameUtil.isJavaMessageType(toType)) {
            if (QNameUtil.isJavaMessageType(fromType)) {
                throw new SwitchYardException("Invalid JAXB Transformer configuration.  The 'from' and 'to' transformation types are both Java types.  Exactly one must be a Java type.");
            }
            return JavaTransformType.XML2JAVA;
        } else {
            throw new SwitchYardException("Invalid JAXB Transformer configuration.  Neither 'from' or 'to' transformation types is a Java type.  Exactly one must be a Java type.");
        }
    }

    private static String getJavaPackage(QName type) {
        return QNameUtil.toJavaMessageType(type).getPackage().getName();
    }
}
