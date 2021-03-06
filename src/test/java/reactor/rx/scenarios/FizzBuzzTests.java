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
package reactor.rx.scenarios;

import java.time.Duration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;
import reactor.AbstractReactorTest;
import reactor.core.publisher.TopicProcessor;
import reactor.core.util.Assert;
import reactor.rx.Fluxion;
import reactor.rx.Promise;
import reactor.rx.subscriber.InterruptableSubscriber;

/**
 * https://github.com/reactor/reactor/issues/500
 *
 * @author nitzanvolman
 * @author Stephane Maldini
 */
public class FizzBuzzTests extends AbstractReactorTest {


	@Test
	public void fizzTest() throws Throwable {
		int numOfItems = 1024;
		int batchSize = 8;
		final Timer timer = new Timer();
		AtomicLong globalCounter = new AtomicLong();

		InterruptableSubscriber<?> c = Fluxion.generate((demand, subscriber) -> {
			System.out.println("demand is " + demand);
			if (!subscriber.isCancelled()) {
				for (int i = 0; i < demand; i++) {
					long curr = globalCounter.incrementAndGet();
					if (curr % 5 == 0 && curr % 3 == 0) subscriber.onNext("FizBuz "+curr+" \r\n");
					else if (curr % 3 == 0) subscriber.onNext("Fiz "+curr);
					else if (curr % 5 == 0) subscriber.onNext("Buz "+curr);
					else subscriber.onNext(String.valueOf(curr) + " ");

					if (globalCounter.get() > numOfItems) {
						subscriber.onComplete();
						return;
					}
				}
			}
		}).log("oooo")
		                                      .flatMap((s) -> Fluxion.yield((sub) -> timer.schedule(new TimerTask() {
			  @Override
			  public void run() {
				  sub.onNext(s);
				  sub.onComplete();
			  }
		  }, 10)))
		                                      .useCapacity(batchSize)
		                                      .log()
		                                      .take(numOfItems + 1)
		                                      .subscribe();

		while (!c.isTerminated()) ;
	}


	@Test
	public void indexBugTest() throws InterruptedException {
		int numOfItems = 20;

		//this line causes an java.lang.ArrayIndexOutOfBoundsException unless there is a break point in ZipAction
		// .createSubscriber()
		TopicProcessor<String> ring = TopicProcessor.create("test", 1024);

		//this line works
//        Broadcaster<String> ring = Broadcaster.create(Environment.get());

		Fluxion<String> stream = Fluxion.fromProcessor(ring.start());

		Fluxion<String> stream2 = stream
				.zipWith(Fluxion.generate((d, s) -> {
			  for (int i = 0; i < d; i++) {
				  if(!s.isCancelled()) {
					  s.onNext(System.currentTimeMillis());
				  }
			  }
		  }), (t1, t2) -> String.format("%s : %s", t1, t2))
				.doOnValueError(Throwable.class, (o, t) -> {
			  System.err.println(t.toString());
			  t.printStackTrace();
		  });

		Promise<List<String>> p = stream2
		  .doOnNext(System.out::println)
		  .buffer(numOfItems)
		  .promise();

		for (int curr = 0; curr < numOfItems; curr++) {
			if (curr % 5 == 0 && curr % 3 == 0) ring.onNext("FizBuz"+curr);
			else if (curr % 3 == 0) ring.onNext("Fiz"+curr);
			else if (curr % 5 == 0) ring.onNext("Buz"+curr);
			else ring.onNext(String.valueOf(curr));
		}

		Assert.isTrue(p.await(Duration.ofSeconds(5)) != null, "Has not returned list");

	}
}

