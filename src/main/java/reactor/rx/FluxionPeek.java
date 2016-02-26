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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Fuseable.ConditionalSubscriber;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.fn.Consumer;
import reactor.fn.LongConsumer;
import reactor.rx.FluxionPeekFuseable.PeekConditionalSubscriber;
import reactor.rx.FluxionPeekFuseable.PeekFuseableSubscriber;

/**
 * Peek into the lifecycle events and signals of a sequence.
 * <p>
 * <p>
 * The callbacks are all optional.
 * <p>
 * <p>
 * Crashes by the lambdas are ignored.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 *
 * @since 2.5
 */
final class FluxionPeek<T> extends FluxionSource<T, T> implements reactor.rx.FluxionPeekHelper<T> {

	final Consumer<? super Subscription> onSubscribeCall;

	final Consumer<? super T> onNextCall;

	final Consumer<? super Throwable> onErrorCall;

	final Runnable onCompleteCall;

	final Runnable onAfterTerminateCall;

	final LongConsumer onRequestCall;

	final Runnable onCancelCall;

	public FluxionPeek(Publisher<? extends T> source,
			Consumer<? super Subscription> onSubscribeCall,
			Consumer<? super T> onNextCall,
			Consumer<? super Throwable> onErrorCall,
			Runnable onCompleteCall,
			Runnable onAfterTerminateCall,
			LongConsumer onRequestCall,
			Runnable onCancelCall) {
		super(source);
		this.onSubscribeCall = onSubscribeCall;
		this.onNextCall = onNextCall;
		this.onErrorCall = onErrorCall;
		this.onCompleteCall = onCompleteCall;
		this.onAfterTerminateCall = onAfterTerminateCall;
		this.onRequestCall = onRequestCall;
		this.onCancelCall = onCancelCall;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(Subscriber<? super T> s) {
		if (source instanceof Fuseable) {
			source.subscribe(new PeekFuseableSubscriber<>(s, this));
			return;
		}
		if (s instanceof ConditionalSubscriber) {
			source.subscribe(new PeekConditionalSubscriber<>((ConditionalSubscriber<? super T>) s, this));
			return;
		}
		source.subscribe(new PeekSubscriber<>(s, this));
	}

	static final class PeekSubscriber<T> implements Subscriber<T>, Subscription, Receiver, Producer {

		final Subscriber<? super T> actual;

		final FluxionPeekHelper<T> parent;

		Subscription s;

		public PeekSubscriber(Subscriber<? super T> actual, FluxionPeekHelper<T> parent) {
			this.actual = actual;
			this.parent = parent;
		}

		@Override
		public void request(long n) {
			if (parent.onRequestCall() != null) {
				try {
					parent.onRequestCall()
					      .accept(n);
				}
				catch (Throwable e) {
					cancel();
					onError(Exceptions.unwrap(e));
					return;
				}
			}
			s.request(n);
		}

		@Override
		public void cancel() {
			if (parent.onCancelCall() != null) {
				try {
					parent.onCancelCall()
					      .run();
				}
				catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					s.cancel();
					onError(Exceptions.unwrap(e));
					return;
				}
			}
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (parent.onSubscribeCall() != null) {
				try {
					parent.onSubscribeCall()
					      .accept(s);
				}
				catch (Throwable e) {
					s.cancel();
					actual.onSubscribe(EmptySubscription.INSTANCE);
					onError(e);
					return;
				}
			}
			this.s = s;
			actual.onSubscribe(this);
		}

		@Override
		public void onNext(T t) {
			if (parent.onNextCall() != null) {
				try {
					parent.onNextCall()
					      .accept(t);
				}
				catch (Throwable e) {
					cancel();
					Exceptions.throwIfFatal(e);
					onError(Exceptions.unwrap(e));
					return;
				}
			}
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {
			if (parent.onErrorCall() != null) {
				Exceptions.throwIfFatal(t);
				parent.onErrorCall()
				      .accept(t);
			}

			actual.onError(t);

			if (parent.onAfterTerminateCall() != null) {
				try {
					parent.onAfterTerminateCall()
					      .run();
				}
				catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					Throwable _e = Exceptions.unwrap(e);
					e.addSuppressed(Exceptions.unwrap(t));
					if (parent.onErrorCall() != null) {
						parent.onErrorCall()
						      .accept(_e);
					}
					actual.onError(_e);
				}
			}
		}

		@Override
		public void onComplete() {
			if (parent.onCompleteCall() != null) {
				try {
					parent.onCompleteCall()
					      .run();
				}
				catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					onError(Exceptions.unwrap(e));
					return;
				}
			}

			actual.onComplete();

			if (parent.onAfterTerminateCall() != null) {
				try {
					parent.onAfterTerminateCall()
					      .run();
				}
				catch (Throwable e) {
					Exceptions.throwIfFatal(e);
					Throwable _e = Exceptions.unwrap(e);
					if (parent.onErrorCall() != null) {
						parent.onErrorCall()
						      .accept(_e);
					}
					actual.onError(_e);
				}
			}
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public Object upstream() {
			return s;
		}
	}

	@Override
	public Consumer<? super Subscription> onSubscribeCall() {
		return onSubscribeCall;
	}

	@Override
	public Consumer<? super T> onNextCall() {
		return onNextCall;
	}

	@Override
	public Consumer<? super Throwable> onErrorCall() {
		return onErrorCall;
	}

	@Override
	public Runnable onCompleteCall() {
		return onCompleteCall;
	}

	@Override
	public Runnable onAfterTerminateCall() {
		return onAfterTerminateCall;
	}

	@Override
	public LongConsumer onRequestCall() {
		return onRequestCall;
	}

	@Override
	public Runnable onCancelCall() {
		return onCancelCall;
	}
}
