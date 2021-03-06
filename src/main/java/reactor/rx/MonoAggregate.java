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
import java.util.function.BiFunction;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.MonoSource;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;

/**
 * Aggregates the source items with an aggregator function and returns the last result.
 *
 * @param <T> the input and output value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class MonoAggregate<T> extends MonoSource<T, T> {

	final BiFunction<T, T, T> aggregator;

	public MonoAggregate(Publisher<? extends T> source, BiFunction<T, T, T> aggregator) {
		super(source);
		this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
	}
	
	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new AggregateSubscriber<>(s, aggregator));
	}
	
	static final class AggregateSubscriber<T> extends DeferredScalarSubscriber<T, T> {
		final BiFunction<T, T, T> aggregator;

		Subscription s;
		
		T result;
		
		boolean done;
		
		public AggregateSubscriber(Subscriber<? super T> actual, BiFunction<T, T, T> aggregator) {
			super(actual);
			this.aggregator = aggregator;
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
				Exceptions.onNextDropped(t);
				return;
			}
			T r = result;
			if (r == null) {
				result = t;
			} else {
				try {
					r = aggregator.apply(r, t);
				} catch (Throwable ex) {
					Exceptions.throwIfFatal(ex);
					s.cancel();
					result = null;
					done = true;
					subscriber.onError(ex);
					return;
				}
				
				if (r == null) {
					s.cancel();
					result = null;
					done = true;
					subscriber.onError(new NullPointerException("The aggregator returned a null value"));
					return;
				}
				
				result = r;
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}
			result = null;
			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			T r = result;
			if (r != null) {
				complete(r);
			} else {
				subscriber.onComplete();
			}
		}
		
		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}
	}
}
