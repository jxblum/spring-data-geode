/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.gemfire.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionService;
import org.apache.geode.internal.cache.LocalRegion;

/**
 * Unit Tests for {@link RegionUtils}.
 *
 * @author John Blum
 * @see org.springframework.data.gemfire.util.RegionUtils
 * @since 2.1.0
 */
public class RegionUtilsUnitTests {

	@Test
	public void assertAllDataPoliciesWithNullPersistentPropertyIsCompatible() {

		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PARTITION, null);
		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PERSISTENT_PARTITION, null);
		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PERSISTENT_REPLICATE, null);
		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.REPLICATE, null);
	}

	@Test
	public void assertNonPersistentDataPolicyWithNoPersistenceIsCompatible() {

		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PARTITION, false);
		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.REPLICATE, false);
	}

	@Test
	public void assertPersistentDataPolicyWithPersistenceIsCompatible() {

		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PERSISTENT_PARTITION, true);
		RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PERSISTENT_REPLICATE, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void assertNonPersistentDataPolicyWithPersistentAttributeThrowsIllegalArgumentException() {

		try {
			RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.REPLICATE, true);
		}
		catch (IllegalArgumentException expected) {

			assertThat(expected).hasMessage("Data Policy [REPLICATE] is not valid when persistent is true");
			assertThat(expected).hasNoCause();

			throw expected;
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void assertPersistentDataPolicyWithNonPersistentAttributeThrowsIllegalArgumentException() {

		try {
			RegionUtils.assertDataPolicyAndPersistentAttributeAreCompatible(DataPolicy.PERSISTENT_PARTITION, false);
		}
		catch (IllegalArgumentException expected) {

			assertThat(expected).hasMessage("Data Policy [PERSISTENT_PARTITION] is not valid when persistent is false");
			assertThat(expected).hasNoCause();

			throw expected;
		}
	}

	@Test
	public void closeRegionIsNullSafe() {
		assertThat(RegionUtils.close((Region<?, ?>) null)).isFalse();
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void closeRegionSuccessfully() {

		Region mockRegion = mock(Region.class);

		assertThat(RegionUtils.close(mockRegion)).isTrue();

		verify(mockRegion, times(1)).close();
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void closeRegionUnsuccessfully() {

		Region mockRegion = mock(Region.class);

		doThrow(new RuntimeException("TEST")).when(mockRegion).close();

		assertThat(RegionUtils.close(mockRegion)).isFalse();

		verify(mockRegion, times(1)).close();
	}

	@Test
	public void nullRegionIsNotCloseable() {
		assertThat(RegionUtils.isCloseable(null)).isFalse();
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void regionIsCloseable() {

		Region mockRegion = mock(Region.class);
		RegionService mockRegionService = mock(RegionService.class);

		when(mockRegion.getRegionService()).thenReturn(mockRegionService);
		when(mockRegionService.isClosed()).thenReturn(false);

		assertThat(RegionUtils.isCloseable(mockRegion)).isTrue();

		verify(mockRegion, times(1)).getRegionService();
		verify(mockRegionService, times(1)).isClosed();
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void regionIsNotCloseable() {

		Region mockRegion = mock(Region.class);
		RegionService mockRegionService = mock(RegionService.class);

		when(mockRegion.getRegionService()).thenReturn(mockRegionService);
		when(mockRegionService.isClosed()).thenReturn(true);

		assertThat(RegionUtils.isCloseable(mockRegion)).isFalse();

		verify(mockRegion, times(1)).getRegionService();
		verify(mockRegionService, times(1)).isClosed();
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void regionWithNoRegionServiceIsNotCloseable() {

		Region mockRegion = mock(Region.class);

		when(mockRegion.getRegionService()).thenReturn(null);

		assertThat(RegionUtils.isCloseable(mockRegion)).isFalse();

		verify(mockRegion, times(1)).getRegionService();
	}

	@Test
	public void localRegionIsLocal() {
		assertThat(RegionUtils.isLocal(mock(LocalRegion.class))).isTrue();
	}

	@Test
	public void nonLocalRegionIsNotLocal() {
		assertThat(RegionUtils.isLocal(mock(Region.class))).isFalse();
	}

	@Test
	public void nullRegionIsNotLocal() {
		assertThat(RegionUtils.isLocal(null)).isFalse();
	}

	@Test
	public void toRegionNameWithRegionIsCorrect() {

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("MockName").when(mockRegion).getName();

		assertThat(RegionUtils.toRegionName(mockRegion)).isEqualTo("MockName");

		verify(mockRegion, times(1)).getName();
	}

	@Test
	public void toRegionNameWithNullRegionIsNullSafe() {
		assertThat(RegionUtils.toRegionName((Region<?, ?>) null)).isNull();
	}

	@Test
	public void toRegionNameWithStringIsCorrect() {

		assertThat(RegionUtils.toRegionName("A")).isEqualTo("A");
		assertThat(RegionUtils.toRegionName("/A")).isEqualTo("A");
		assertThat(RegionUtils.toRegionName("/A/B")).isEqualTo("B");
		assertThat(RegionUtils.toRegionName("/A/B/C")).isEqualTo("C");
		assertThat(RegionUtils.toRegionName("A/B/C")).isEqualTo("C");
		assertThat(RegionUtils.toRegionName("AB/C")).isEqualTo("C");
		assertThat(RegionUtils.toRegionName("AB/C/")).isEqualTo("");
		assertThat(RegionUtils.toRegionName("null")).isEqualTo("null");
		assertThat(RegionUtils.toRegionName("  ")).isEqualTo("  ");
		assertThat(RegionUtils.toRegionName("")).isEqualTo("");
	}

	@Test
	public void toRegionNameWithNullStringIsCorrect() {
		assertThat(RegionUtils.toRegionName((String) null)).isNull();
	}

	@Test
	public void toRegionPathWithRegionHavingPathIsCorrect() {

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("/A/B/C").when(mockRegion).getFullPath();

		assertThat(RegionUtils.toRegionPath(mockRegion)).isEqualTo("/A/B/C");

		verify(mockRegion, times(1)).getFullPath();
	}

	@Test
	public void toRegionPathWithRegionHavingNoPathIsCorrect() {

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn(null).when(mockRegion).getFullPath();

		assertThat(RegionUtils.toRegionPath(mockRegion)).isNull();

		verify(mockRegion, times(1)).getFullPath();
	}

	@Test
	public void toRegionPathWithRegionNotBeginningWithRegionSeparatorIsCorrect() {

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("A/B").when(mockRegion).getFullPath();

		assertThat(RegionUtils.toRegionPath(mockRegion)).isEqualTo("/A/B");

		verify(mockRegion, times(1)).getFullPath();
	}

	@Test
	public void toRegionPathWithNullRegionIsNullSafe() {
		assertThat(RegionUtils.toRegionPath((Region<?, ?>) null)).isNull();
	}

	@Test
	public void toRegionPathWithStringIsCorrect() {

		assertThat(RegionUtils.toRegionPath("/A")).isEqualTo("/A");
		assertThat(RegionUtils.toRegionPath("A/B")).isEqualTo("/A/B");
		assertThat(RegionUtils.toRegionPath("/A/B/C")).isEqualTo("/A/B/C");
		assertThat(RegionUtils.toRegionPath("A")).isEqualTo("/A");
		assertThat(RegionUtils.toRegionPath((String) null)).isEqualTo("/null");
	}
}
