/*
<<<<<<< Updated upstream
 * Copyright 2002-2018 the original author or authors.
=======
 * Copyright 2002-2013 the original author or authors.
>>>>>>> Stashed changes
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.data.gemfire.function.config;

import java.util.Map;

import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.data.gemfire.util.SpringUtils;
import org.springframework.util.Assert;

/**
 * Function execution configuration used by bean definition builders
 *
 * @author David Turanski
 * @author John Blum
 */
class FunctionExecutionConfiguration  {

	private Class<?> functionExecutionInterface;

	private final Map<String, Object> annotationAttributes;

	private final String annotationType;

	/* constructor for testing purposes only! */
	FunctionExecutionConfiguration() {
		this.annotationType = null;
		this.annotationAttributes = null;
	}

	FunctionExecutionConfiguration(ScannedGenericBeanDefinition beanDefinition, String annotationType) {

		try {
			this.annotationType = annotationType;
			this.annotationAttributes = beanDefinition.getMetadata().getAnnotationAttributes(annotationType, true);
			this.functionExecutionInterface = beanDefinition.resolveBeanClass(beanDefinition.getClass().getClassLoader());

			Assert.isTrue(this.functionExecutionInterface != null && this.functionExecutionInterface.isInterface(),
				String.format("The annotation %1$s only applies to an interface. It is not valid for type %2$s",
					annotationType, SpringUtils.nullSafeName(this.functionExecutionInterface)));
		}
		catch (ClassNotFoundException cause) {
			throw new RuntimeException(cause);
		}
	}

	String getAnnotationType() {
		return this.annotationType;
	}

	Object getAttribute(String name) {
		return this.annotationAttributes.get(name);
	}

	Map<String, Object> getAttributes() {
		return this.annotationAttributes;
	}

	Class<?> getFunctionExecutionInterface() {
		return this.functionExecutionInterface;
	}
}
