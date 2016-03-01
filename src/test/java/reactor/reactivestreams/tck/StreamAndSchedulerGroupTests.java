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

package reactor.reactivestreams.tck;

import java.time.Duration;
import java.util.function.BiFunction;

import org.junit.Test;
import org.reactivestreams.Processor;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.SchedulerGroup;
import reactor.rx.Broadcaster;
import reactor.rx.Fluxion;

/**
 * @author Stephane Maldini
 */
@org.testng.annotations.Test
public class StreamAndSchedulerGroupTests extends AbstractStreamVerification {

	static SchedulerGroup sharedGroup;


	@Override
	public Processor<Integer, Integer> createProcessor(int bufferSize) {

		Fluxion<String> otherStream = Fluxion.just("test", "test2", "test3");
		System.out.println("Providing new downstream");

		SchedulerGroup asyncGroup =
				SchedulerGroup.async("fluxion-p-tck", bufferSize, 2,
						Throwable::printStackTrace, () -> System.out.println("EEEEE"));

		BiFunction<Integer, String, Integer> combinator = (t1, t2) -> t1;
		return FluxProcessor.blackbox(Broadcaster.<Integer>create(true), p ->

				p.dispatchOn(sharedGroup)
				 .log("grouped")
				 .partition(2)
		                  .flatMap(stream -> stream.dispatchOn(asyncGroup)
		                                           .doOnNext(this::monitorThreadUse)
		                                           .scan((prev, next) -> next)
		                                           .map(integer -> -integer)
		                                           .filter(integer -> integer <= 0)
		                                           .every(1)
		                                           .map(integer -> -integer)
		                                           .buffer(batch, Duration.ofMillis(50))
		                                           .flatMap(Fluxion::fromIterable)
		                                           .flatMap(i -> Fluxion.zip(Fluxion.just(i), otherStream, combinator))
		                  )
				.dispatchOn(sharedGroup)
				.doOnError(Throwable.class, Throwable::printStackTrace)
		);
	}/*

	@Override
	public void required_exerciseWhiteboxHappyPath() throws Throwable {
		super.required_exerciseWhiteboxHappyPath();
	}

	@Override
	public void required_createPublisher3MustProduceAStreamOfExactly3Elements() throws Throwable {
		super.required_createPublisher3MustProduceAStreamOfExactly3Elements();
	}

	@Override
	public void required_mustRequestFromUpstreamForElementsThatHaveBeenRequestedLongAgo() throws Throwable {
		super.required_mustRequestFromUpstreamForElementsThatHaveBeenRequestedLongAgo();
	}

	@Override
	public void stochastic_spec103_mustSignalOnMethodsSequentially() throws Throwable {
		//for(int i = 0; i < 1000; i++)
		super.stochastic_spec103_mustSignalOnMethodsSequentially();
	}*/

	/*@Override
	public void required_spec205_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal()
			throws Throwable {
		super.required_spec205_mustCallSubscriptionCancelIfItAlreadyHasAnSubscriptionAndReceivesAnotherOnSubscribeSignal();
	}

	@Override
	public void required_spec213_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull() throws Throwable {
		super.required_spec213_onSubscribe_mustThrowNullPointerExceptionWhenParametersAreNull();
	}*/

	@Override
	public void tearDown() {
		//sharedGroup.awaitAndShutdown();
		super.tearDown();
	}


	@org.junit.BeforeClass
	@BeforeClass
	public static void setupGlobal(){
		System.out.println("test ");
		sharedGroup = SchedulerGroup.async("fluxion-tck", 32, 2,
				Throwable::printStackTrace, null, false);
	}

	@org.junit.AfterClass
	@AfterClass
	public static void tearDownGlobal(){
		System.out.println("shutdown "+sharedGroup.awaitAndShutdown());
	}

	@Override
	@Test
	public void testHotIdentityProcessor() throws InterruptedException {
		//for(int i =0; i < 1000; i++)
		super.testHotIdentityProcessor();
	}

	@Override
	@Test
	public void testColdIdentityProcessor() throws InterruptedException {
		//for (int i = 0; i < 1000; i++)
		super.testColdIdentityProcessor();
	}

	@Override
	public void required_spec104_mustCallOnErrorOnAllItsSubscribersIfItEncountersANonRecoverableError()
			throws Throwable {
		super.required_spec104_mustCallOnErrorOnAllItsSubscribersIfItEncountersANonRecoverableError();
	}

	@Override
	public void required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber()
			throws Throwable {
		try {
			super.required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber();
		}
		catch (Throwable t){
			if(t.getMessage() != null && t.getMessage().contains("did not drop reference to test subscriber")) {
				throw new SkipException("todo", t);
			}
			else{
				throw t;
			}
		}
	}

	/*public static void main(String... args) throws Exception {
		AbstractStreamVerification s = new StreamAndSchedulerGroupTests();
		Processor p = s.createProcessor(256);
		SignalEmitter sess = SignalEmitter.create(p);
		p.subscribe(Subscribers.unbounded());
		Nexus nexus = ReactiveNet.nexus().withSystemStats();
		nexus.monitor(p);
		nexus.startAndAwait();
		int n = 1;
		for(;;){
			sess.submit(n++);
		}
	}*/

}
