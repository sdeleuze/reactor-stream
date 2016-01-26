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

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.graph.Publishable;
import reactor.core.subscriber.SubscriberDeferredScalar;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.fn.Predicate;

/**
 * Emits a single boolean true if any of the values of the source sequence match
 * the predicate.
 * <p>
 * The implementation uses short-circuit logic and completes with true if
 * the predicate matches a value.
 *
 * @param <T> the source value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoAny<T> extends reactor.core.publisher.Mono.MonoBarrier<T, Boolean> {

	final Predicate<? super T> predicate;

	public MonoAny(Publisher<? extends T> source, Predicate<? super T> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public void subscribe(Subscriber<? super Boolean> s) {
		source.subscribe(new AnySubscriber<T>(s, predicate));
	}

	static final class AnySubscriber<T> extends SubscriberDeferredScalar<T, Boolean>
			implements Publishable {
		final Predicate<? super T> predicate;

		Subscription s;

		boolean done;

		public AnySubscriber(Subscriber<? super Boolean> actual, Predicate<? super T> predicate) {
			super(actual);
			this.predicate = predicate;
		}

		@Override
		public void cancel() {
			s.cancel();
			super.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				subscriber.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {

			if (done) {
				return;
			}

			boolean b;

			try {
				b = predicate.test(t);
			} catch (Throwable e) {
				done = true;
				s.cancel();
				Exceptions.throwIfFatal(e);
				subscriber.onError(Exceptions.unwrap(e));
				return;
			}
			if (b) {
				done = true;
				s.cancel();

				complete(true);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}
			done = true;

			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			complete(false);
		}

		@Override
		public boolean isTerminated() {
			return done;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object connectedInput() {
			return predicate;
		}
	}
}