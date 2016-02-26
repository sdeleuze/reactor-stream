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

import org.junit.Test;
import reactor.core.test.TestSubscriber;
import reactor.core.util.Assert;

public class MonoAllTest {

	@Test(expected = NullPointerException.class)
	public void sourceNull() {
		new MonoAll<>(null, v -> true);
	}

	@Test(expected = NullPointerException.class)
	public void predicateNull() {
		new MonoAll<>(null, null);
	}

	@Test
	public void normal() {
		TestSubscriber<Boolean> ts = new TestSubscriber<>();

		new MonoAll<>(new FluxionRange(1, 10), v -> true).subscribe(ts);

		ts.assertValues(true)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void normalBackpressured() {
		TestSubscriber<Boolean> ts = new TestSubscriber<>(0);

		new MonoAll<>(new FluxionRange(1, 10), v -> true).subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertNoError();

		ts.request(1);

		ts.assertValues(true)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void someMatch() {
		TestSubscriber<Boolean> ts = new TestSubscriber<>();

		new MonoAll<>(new FluxionRange(1, 10), v -> v < 6).subscribe(ts);

		ts.assertValues(false)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void someMatchBackpressured() {
		TestSubscriber<Boolean> ts = new TestSubscriber<>(0);

		new MonoAll<>(new FluxionRange(1, 10), v -> v < 6).subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertNoError();

		ts.request(1);

		ts.assertValues(false)
		  .assertComplete()
		  .assertNoError();
	}

	@Test
	public void predicateThrows() {
		TestSubscriber<Boolean> ts = new TestSubscriber<>();

		new MonoAll<>(new FluxionRange(1, 10), v -> {
			throw new RuntimeException("forced failure");
		}).subscribe(ts);

		ts.assertNoValues()
		  .assertNotComplete()
		  .assertError(RuntimeException.class)
		  .assertErrorWith(e -> Assert.isTrue(e.getMessage()
		                                       .contains("forced failure")));
	}

}
