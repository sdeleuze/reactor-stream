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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Loopback;
import reactor.core.flow.Receiver;
import reactor.core.state.Completable;
import reactor.core.util.Exceptions;

/**
 * A Stream that emits only one value and then complete.
 * <p>
 * Since the fluxion retains the value in a final field, any {@link this#subscribe(Subscriber)} will
 * replay the value. This is a "Cold" fluxion.
 * <p>
 * Create such fluxion with the provided factory, E.g.:
 * <pre>
 * {@code
 * Streams.just(1).consume(
 *    log::info,
 *    log::error,
 *    (-> log.info("complete"))
 * )
 * }
 * </pre>
 * Will log:
 * <pre>
 * {@code
 * 1
 * complete
 * }
 * </pre>
 *
 * @author Stephane Maldini
 */
final class FluxionJust<T> extends Fluxion<T> implements Fuseable.ScalarSupplier<T>, Loopback {

	final public static FluxionJust<?> EMPTY = new FluxionJust<>(null);

	final T value;

	@SuppressWarnings("unchecked")
	public FluxionJust(T value) {
		this.value = value;
	}

	@Override
	public T get() {
		return value;
	}

	@Override
	public void subscribe(final Subscriber<? super T> subscriber) {
		try {
			subscriber.onSubscribe(new WeakScalarSubscription<>(value, subscriber));
		}
		catch (Throwable throwable) {
			Exceptions.throwIfFatal(throwable);
			subscriber.onError(throwable);
		}
	}

	@Override
	public Object connectedOutput() {
		return value;
	}

	@Override
	public String toString() {
		return "singleValue=" + value;
	}

	static final class WeakScalarSubscription<T> implements Subscription, Receiver, Completable {

		boolean terminado;
		final T                     value;
		final Subscriber<? super T> subscriber;

		public WeakScalarSubscription(T value, Subscriber<? super T> subscriber) {
			this.value = value;
			this.subscriber = subscriber;
		}

		@Override
		public void request(long elements) {
			if (terminado) {
				return;
			}

			terminado = true;
			if (value != null) {
				subscriber.onNext(value);
			}
			subscriber.onComplete();
		}

		@Override
		public void cancel() {
			terminado = true;
		}

		@Override
		public boolean isStarted() {
			return !terminado;
		}

		@Override
		public boolean isTerminated() {
			return terminado;
		}

		@Override
		public Object upstream() {
			return value;
		}
	}
}
