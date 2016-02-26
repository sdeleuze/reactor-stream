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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.state.Failurable;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.fn.Supplier;

/**
 * Emits a constant or generated Throwable instance to Subscribers.
 *
 * @param <T> the value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class FluxionError<T>
		extends Fluxion<T>
		implements Failurable {

	final Supplier<? extends Throwable> supplier;
	
	final boolean whenRequested;

	public FluxionError(Throwable error) {
		this(create(error), false);
	}

	public FluxionError(Throwable error, boolean whenRequested) {
		this(create(error), whenRequested);
	}

	static Supplier<Throwable> create(final Throwable error) {
		Objects.requireNonNull(error);
		return new Supplier<Throwable>() {
			@Override
			public Throwable get() {
				return error;
			}
		};
	}

	public FluxionError(Supplier<? extends Throwable> supplier) {
		this(supplier, false);
	}

	
	public FluxionError(Supplier<? extends Throwable> supplier, boolean whenRequested) {
		this.supplier = Objects.requireNonNull(supplier);
		this.whenRequested = whenRequested;
	}

	@Override
	public Throwable getError() {
		return supplier.get();
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		Throwable e;

		try {
			e = supplier.get();
		} catch (Throwable ex) {
			e = ex;
		}

		if (e == null) {
			e = new NullPointerException("The Throwable returned by the supplier is null");
		}

		if (whenRequested) {
			s.onSubscribe(new ErrorSubscription(s, e));
		} else {
			EmptySubscription.error(s, e);
		}
	}
	
	static final class ErrorSubscription
	implements Subscription {
		final Subscriber<?> actual;
		
		final Throwable error;
		
		volatile int once;
		static final AtomicIntegerFieldUpdater<ErrorSubscription> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(ErrorSubscription.class, "once");
		
		public ErrorSubscription(Subscriber<?> actual, Throwable error) {
			this.actual = actual;
			this.error = error;
		}
		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				if (ONCE.compareAndSet(this, 0, 1)) {
					actual.onError(error);
				}
			}
		}
		@Override
		public void cancel() {
			once = 1;
		}
	}
}
