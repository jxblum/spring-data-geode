/*
 * Copyright 2020 the original author or authors.
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
package org.springframework.data.gemfire.repository;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.geode.cache.client.ClientRegionShortcut;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.gemfire.config.annotation.ClientCacheApplication;
import org.springframework.data.gemfire.config.annotation.EnableEntityDefinedRegions;
import org.springframework.data.gemfire.mapping.GemfireMappingContext;
import org.springframework.data.gemfire.mapping.annotation.Region;
import org.springframework.data.gemfire.repository.query.annotation.Trace;
import org.springframework.data.gemfire.repository.support.GemfireRepositoryFactoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Integration Tests for 2-phased paged OQL query execution and SDG {@link Repository Repositories}.
 *
 * @author John Blum
 * @see org.junit.Test
 * @see org.springframework.data.domain.Page
 * @see org.springframework.data.domain.Pageable
 * @see org.springframework.data.gemfire.repository.GemfireRepository
 * @see org.springframework.test.context.ContextConfiguration
 * @see org.springframework.test.context.junit4.SpringRunner
 * @since 2.4.0
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = TwoPhasedPagedQueryExecutionRepositoryIntegrationTests.TestConfiguration.class)
@SuppressWarnings("unused")
public class TwoPhasedPagedQueryExecutionRepositoryIntegrationTests {

	private Collection<User> users;

	@Autowired
	private UserRepository userRepository;

	@BeforeClass
	public static void setup() {
		System.setProperty("spring.data.gemfire.query.limit.threshold", "3");
	}

	@AfterClass
	public static void tearDown() {
		System.clearProperty("spring.data.gemfire.query.limit.threshold");
	}

	@Before
	public void initializeData() {

		assertThat(this.userRepository).isNotNull();

		if (this.userRepository.count() == 0L) {

			this.users = new HashSet<>(Arrays.asList(
				User.newUser("Jon Doe").identifiedBy(1L).as(Role.ADMIN),
				User.newUser("Jane Doe").identifiedBy(2L).as(Role.ADMIN),
				User.newUser("Bob Doe").identifiedBy(3L).as(Role.WORKER),
				User.newUser("Cookie Doe").identifiedBy(4L).as(Role.GUEST),
				User.newUser("Fro Doe").identifiedBy(5L).as(Role.GUEST),
				User.newUser("Joe Doe").identifiedBy(6L).as(Role.WORKER),
				User.newUser("Lan Doe").identifiedBy(7L).as(Role.ADMIN),
				User.newUser("Moe Doe").identifiedBy(8L).as(Role.WORKER),
				User.newUser("Pie Doe").identifiedBy(9L).as(Role.GUEST),
				User.newUser("Play Doe").identifiedBy(10L).as(Role.GUEST),
				User.newUser("Sour Doe").identifiedBy(11L).as(Role.GUEST),
				User.newUser("Jack Black").identifiedBy(12L).as(Role.ADMIN),
				User.newUser("Jack Handy").identifiedBy(13L).as(Role.WORKER),
				User.newUser("Sandy Handy").identifiedBy(14L).as(Role.WORKER)
			));

			for (User user : this.users) {
				assertThat(this.userRepository.save(user)).isEqualTo(user);
			}
		}

		assertThat(this.userRepository.count()).isEqualTo(14L);
	}

	private User find(String name) {

		return this.users.stream()
			.filter(user -> user.getName().equalsIgnoreCase(name))
			.findFirst()
			.orElse(null);
	}

	@Test
	public void pagedListIsCorrect() {

		List<User> users =
			this.userRepository.findByRole(Role.WORKER, PageRequest.of(1, 3, Sort.by("name")));

		assertThat(users).isNotNull();
		assertThat(users).containsExactly(find("Bob Doe"), find("Joe Doe"), find("Moe Doe"));
	}

	@Test
	public void pagedQueryResultSetsAreCorrect() {

		Pageable pageOneRequest = PageRequest.of(0, 3, Sort.by("name"));

		Page<User> pageOne = this.userRepository.findByNameLike("%Doe", pageOneRequest);

		assertThat(pageOne).isNotNull();
		assertThat(pageOne).containsExactly(find("Bob Doe"), find("Cookie Doe"), find("Fro Doe"));

		Pageable pageTwoRequest = pageOneRequest.next();

		Page<User> pageTwo = this.userRepository.findByNameLike("%Doe", pageTwoRequest);

		assertThat(pageTwo).isNotNull();
		assertThat(pageTwo).containsExactly(find("Jane Doe"), find("Joe Doe"), find("Jon Doe"));

		Pageable pageThreeRequest = pageTwoRequest.next();

		Page<User> pageThree = this.userRepository.findByNameLike("%Doe", pageThreeRequest);

		assertThat(pageThree).isNotNull();
		assertThat(pageThree).containsExactly(find("Lan Doe"), find("Moe Doe"), find("Pie Doe"));

		Pageable pageFourRequest = pageThreeRequest.next();

		Page<User> pageFour = this.userRepository.findByNameLike("%Doe", pageFourRequest);

		assertThat(pageFour).isNotNull();
		assertThat(pageFour).containsExactly(find("Play Doe"), find("Sour Doe"));

		Pageable pageFiveRequest = pageFourRequest.next();

		Page<User> pageFive = this.userRepository.findByNameLike("%Doe", pageFiveRequest);

		assertThat(pageFive).isNotNull();
		assertThat(pageFive).isEmpty();
	}

	@ClientCacheApplication
	@EnableEntityDefinedRegions(basePackageClasses = User.class, clientRegionShortcut = ClientRegionShortcut.LOCAL,
		includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = User.class))
	static class TestConfiguration {

		@Bean
		GemfireRepositoryFactoryBean<UserRepository, User, Long> userRepository() {

			GemfireRepositoryFactoryBean<UserRepository, User, Long> userRepository =
				new GemfireRepositoryFactoryBean<>(UserRepository.class);

			userRepository.setGemfireMappingContext(new GemfireMappingContext());

			return userRepository;
		}
	}

	enum Role { ADMIN, WORKER, GUEST }

	@Getter
	@Region("Users")
	@ToString(of = "name")
	@EqualsAndHashCode(of = "name")
	@RequiredArgsConstructor(staticName = "newUser")
	static class User {

		@Id
		private Long id;

		private Role role;

		@lombok.NonNull
		private final String name;

		/*
		@PersistenceConstructor
		protected User(Long id, String name, Role role) {

			this.id = id;
			this.name = name;
			this.role = role;
		}
		*/

		public @NonNull User as(@Nullable Role role) {
			this.role = role;
			return this;
		}

		public @NonNull User identifiedBy(@Nullable Long id) {
			this.id = id;
			return this;
		}
	}

	interface UserRepository extends GemfireRepository<User, Long> {

		@Trace
		Page<User> findByNameLike(String name, Pageable pageRequest);

		@Trace
		List<User> findByRole(Role role, Pageable pageRequest);

	}
}
