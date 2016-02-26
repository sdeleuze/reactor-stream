/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rx;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.test.TestSubscriber;
import reactor.core.util.Assert;

public class MonoCollectTest {

	@Test(expected = NullPointerException.class)
	public void nullSource() {
		new MonoCollect<>(null, () -> 1, (a, b) -> {
		});
	}

	@Test(expected = NullPointerException.class)
	public void nullSupplier() {
		new MonoCollect<>(Mono.never(), null, (a, b) -> {
		});
	}

	@Test(expected = NullPointerException.class)
	public void nullAction() {
		new MonoCollect<>(Mono.never(), () -> 1, null);
	}

	@Test
	public void normal() {
		TestSubscriber<ArrayList<Integer>> ts = new TestSubscriber<>();

		new MonoCollect<>(new FluxionRange(1, 10), ArrayList<Integer>::new, (a, b) -> a.add(b)).subscribe(ts);

		ts.assertValues(new ArrayList<>(Arrays.<Integer>asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void normalBackpressured() {
		TestSubscriber<ArrayList<Integer>> ts = new TestSubscriber<>(0);

		new MonoCollect<>(new FluxionRange(1, 10), ArrayList<Integer>::new, (a, b) -> a.add(b)).subscribe(ts);

		ts.assertNoValues()
		  .assertNoError()
		  .assertNotComplete();

		ts.request(2);

		ts.assertValues(new ArrayList<>(Arrays.<Integer>asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
		  .assertNoError()
		  .assertComplete();
	}

	@Test
	public void supplierThrows() {
		TestSubscriber<Object> ts = new TestSubscriber<>();

		new MonoCollect<>(new FluxionRange(1, 10), () -> {
			throw new RuntimeException("forced failure");
		}, (a, b) -> {
		}).subscribe(ts);

		ts.assertNoValues()
		  .assertError(RuntimeException.class)
		  .assertErrorWith( e -> Assert.isTrue(e.getMessage().contains("forced failure")))
		  .assertNotComplete();

	}

	@Test
	public void supplierReturnsNull() {
		TestSubscriber<Object> ts = new TestSubscriber<>();

		new MonoCollect<>(new FluxionRange(1, 10), () -> null, (a, b) -> {
		}).subscribe(ts);

		ts.assertNoValues()
		  .assertError(NullPointerException.class)
		  .assertNotComplete();
	}

	@Test
	public void actionThrows() {
		TestSubscriber<Object> ts = new TestSubscriber<>();

		new MonoCollect<>(new FluxionRange(1, 10), () -> 1, (a, b) -> {
			throw new RuntimeException("forced failure");
		}).subscribe(ts);

		ts.assertNoValues()
		  .assertError(RuntimeException.class)
		  .assertErrorWith( e -> Assert.isTrue(e.getMessage().contains("forced failure")))
		  .assertNotComplete();

	}

}
