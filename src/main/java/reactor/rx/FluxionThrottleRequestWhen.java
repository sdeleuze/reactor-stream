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

import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.state.Backpressurable;
import reactor.core.timer.Timer;
import reactor.rx.subscriber.SubscriberWithDemand;

/**
 * @author Stephane Maldini
 * @since 2.0, 2.5
 */
final class FluxionThrottleRequestWhen<T> extends FluxionSource<T, T> {

	private final Timer                                                                          timer;
	private final Function<? super Fluxion<? extends Long>, ? extends Publisher<? extends Long>> predicate;

	public FluxionThrottleRequestWhen(Publisher<T> source, Timer timer,
			Function<? super Fluxion<? extends Long>, ? extends Publisher<? extends Long>> predicate) {
		super(source);
		this.timer = timer;
		this.predicate = predicate;
	}

	@Override
	public Subscriber<? super T> apply(Subscriber<? super T> subscriber) {
		return new ThrottleRequestWhenAction<>(subscriber, timer, predicate);
	}

	static final class ThrottleRequestWhenAction<T> extends SubscriberWithDemand<T, T> {

		private final Broadcaster<Long> throttleStream;

		public ThrottleRequestWhenAction(Subscriber<? super T> actual,
				Timer timer,
				Function<? super Fluxion<? extends Long>, ? extends Publisher<? extends Long>> predicate) {

			super(actual);
			this.throttleStream = Broadcaster.create();
			Publisher<? extends Long> afterRequestStream = predicate.apply(throttleStream);
			afterRequestStream.subscribe(new ThrottleSubscriber());
		}

		@Override
		protected void doRequested(long b, long elements) {
			throttleStream.onNext(elements);
		}

		@Override
		protected void checkedComplete() {
			throttleStream.onComplete();
		}

		private class ThrottleSubscriber implements Subscriber<Long>, Backpressurable {

			Subscription s;

			@Override
			public long getCapacity() {
				return ThrottleRequestWhenAction.this
						.getCapacity();
			}

			@Override
			public long getPending() {
				return ThrottleRequestWhenAction.this.getPending();
			}

			@Override
			public void onSubscribe(Subscription s) {
				this.s = s;
				s.request(1L);
			}

			@Override
			public void onNext(Long o) {
				//s.cancel();
				//publisher.subscribe(this);
				if (o > 0) {
					requestMore(o);
				}
				s.request(1L);
			}

			@Override
			public void onError(Throwable t) {
				cancel();
				subscriber.onError(t);
			}

			@Override
			public void onComplete() {
				cancel();
			}
		}
	}

}
