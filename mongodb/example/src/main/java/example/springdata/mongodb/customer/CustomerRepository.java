/*
 * Copyright 2014-2021 the original author or authors.
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
package example.springdata.mongodb.customer;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Repository interface to manage {@link Customer} instances.
 *
 * @author Oliver Gierke
 */
public interface CustomerRepository extends CrudRepository<Customer, String> {

	/**
	 * Derived query using dynamic sort information.
	 *
	 * @param lastname
	 * @param sort
	 * @return
	 */
	List<Customer> findByLastname(String lastname, Sort sort);

	/**
	 * Derived query reducing result size to a given {@link Limit}.
	 *
	 * @param lastname
	 * @param maxResults the maximum number of results returned.
	 * @return
	 */
	List<Customer> findByLastname(String lastname, Limit maxResults);

	/**
	 * Showcase for a repository query using geospatial functionality.
	 *
	 * @param point
	 * @param distance
	 * @return
	 */
	GeoResults<Customer> findByAddressLocationNear(Point point, Distance distance);

	@Query("{}")
	Stream<Customer> findAllByCustomQueryWithStream();
}
