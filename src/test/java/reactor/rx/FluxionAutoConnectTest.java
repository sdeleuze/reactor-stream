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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.test.TestSubscriber;

public class FluxionAutoConnectTest {

	/*@Test
	public void constructors() {
		ConstructorTestBuilder ctb = new ConstructorTestBuilder(StreamAutoConnect.class);
		
		ctb.addRef("source", Fluxion.never().publish());
		ctb.addInt("n", 1, Integer.MAX_VALUE);
		ctb.addRef("cancelSupport", (Consumer<Runnable>)r -> { });
		
		ctb.test();
	}*/
	
	@Test
	public void connectImmediately() {
		EmitterProcessor<Integer> e = EmitterProcessor.create();
		e.start();
		FluxionProcessor<Integer, Integer> sp = FluxionProcessor.fromProcessor(e);
		
		AtomicReference<Runnable> cancel = new AtomicReference<>();
		
		sp.publish().autoConnect(0, cancel::set);
		
		Assert.assertNotNull(cancel.get());
		Assert.assertTrue("sp has no subscribers?", e.downstreamCount() != 0);

		cancel.get().run();
		Assert.assertFalse("sp has subscribers?", e.downstreamCount() != 0);
	}

	@Test
	public void connectAfterMany() {
		EmitterProcessor<Integer> e = EmitterProcessor.create();
		e.start();
		FluxionProcessor<Integer, Integer> sp = FluxionProcessor.fromProcessor(e);
		
		AtomicReference<Runnable> cancel = new AtomicReference<>();
		
		Fluxion<Integer> p = sp.publish().autoConnect(2, cancel::set);
		
		Assert.assertNull(cancel.get());
		Assert.assertFalse("sp has subscribers?", e.downstreamCount() != 0);
		
		p.subscribe(new TestSubscriber<>());
		
		Assert.assertNull(cancel.get());
		Assert.assertFalse("sp has subscribers?", e.downstreamCount() != 0);

		p.subscribe(new TestSubscriber<>());

		Assert.assertNotNull(cancel.get());
		Assert.assertTrue("sp has no subscribers?", e.downstreamCount() != 0);
		
		cancel.get().run();
		Assert.assertFalse("sp has subscribers?", e.downstreamCount() != 0);
	}
}
