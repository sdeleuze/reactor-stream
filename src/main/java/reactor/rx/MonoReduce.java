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
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;

/**
 * Aggregates the source values with the help of an accumulator
 * function and emits the the final accumulated value.
 *
 * @param <T> the source value type
 * @param <R> the accumulated result type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoReduce<T, R> extends reactor.core.publisher.MonoSource<T, R> {

	final Supplier<R> initialSupplier;

	final BiFunction<R, ? super T, R> accumulator;

	public MonoReduce(Publisher<? extends T> source, Supplier<R> initialSupplier,
						   BiFunction<R, ? super T, R> accumulator) {
		super(source);
		this.initialSupplier = Objects.requireNonNull(initialSupplier, "initialSupplier");
		this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
	}

	@Override
	public void subscribe(Subscriber<? super R> s) {
		R initialValue;

		try {
			initialValue = initialSupplier.get();
		} catch (Throwable e) {
			EmptySubscription.error(s, e);
			return;
		}

		if (initialValue == null) {
			EmptySubscription.error(s, new NullPointerException("The initial value supplied is null"));
			return;
		}

		source.subscribe(new ReduceSubscriber<>(s, accumulator, initialValue));
	}

	static final class ReduceSubscriber<T, R>
			extends DeferredScalarSubscriber<T, R>
			implements Receiver {

		final BiFunction<R, ? super T, R> accumulator;

		Subscription s;

		boolean done;

		public ReduceSubscriber(Subscriber<? super R> actual, BiFunction<R, ? super T, R> accumulator,
										 R value) {
			super(actual);
			this.accumulator = accumulator;
			this.value = value;
		}

		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}

		@Override
		public void setValue(R value) {
			// value already saved
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
			R v;

			try {
				v = accumulator.apply(value, t);
			} catch (Throwable e) {
				cancel();
				Exceptions.throwIfFatal(e);
				onError(Exceptions.unwrap(e));
				return;
			}

			if (v == null) {
				cancel();

				onError(new NullPointerException("The accumulator returned a null value"));
				return;
			}

			value = v;
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

			complete(value);
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
			return accumulator;
		}
	}
}
