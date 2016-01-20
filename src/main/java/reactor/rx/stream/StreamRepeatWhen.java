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
package reactor.rx.stream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.ProcessorEmitter;
import reactor.core.subscriber.SubscriberMultiSubscription;
import reactor.core.util.DeferredSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.fn.Function;
import reactor.rx.subscriber.SerializedSubscriber;

/**
 * Repeats a source when a companion sequence
 * signals an item in response to the main's completion signal
 * <p>
 * <p>If the companion sequence signals when the main source is active, the repeat
 * attempt is suppressed and any terminal signal will terminate the main source with the same signal immediately.
 *
 * @param <T> the source value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamRepeatWhen<T> extends StreamBarrier<T, T> {

	final Function<? super reactor.rx.Stream<Long>, ? extends Publisher<? extends Object>> whenSourceFactory;

	public StreamRepeatWhen(Publisher<? extends T> source,
			Function<? super reactor.rx.Stream<Long>, ? extends Publisher<? extends Object>> whenSourceFactory) {
		super(source);
		this.whenSourceFactory = Objects.requireNonNull(whenSourceFactory, "whenSourceFactory");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		StreamRepeatWhenOtherSubscriber other = new StreamRepeatWhenOtherSubscriber();
		other.completionSignal.onSubscribe(EmptySubscription.INSTANCE);

		SerializedSubscriber<T> serial = new SerializedSubscriber<>(s);

		StreamRepeatWhenMainSubscriber<T> main = new StreamRepeatWhenMainSubscriber<>(serial, other.completionSignal,
				source);
		other.main = main;

		serial.onSubscribe(main);

		Publisher<?> p;

		try {
			p = whenSourceFactory.apply(other);
		} catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			s.onError(Exceptions.unwrap(e));
			return;
		}

		if (p == null) {
			s.onError(new NullPointerException("The whenSourceFactory returned a null Publisher"));
			return;
		}

		p.subscribe(other);

		if (!main.cancelled) {
			source.subscribe(main);
		}
	}

	static final class StreamRepeatWhenMainSubscriber<T> extends SubscriberMultiSubscription<T, T> {

		final DeferredSubscription otherArbiter;

		final Subscriber<Long> signaller;

		final Publisher<? extends T> source;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamRepeatWhenMainSubscriber> WIP =
				AtomicIntegerFieldUpdater.newUpdater(StreamRepeatWhenMainSubscriber.class, "wip");

		volatile boolean cancelled;

		long produced;

		public StreamRepeatWhenMainSubscriber(Subscriber<? super T> actual, Subscriber<Long> signaller,
				Publisher<? extends T> source) {
			super(actual);
			this.signaller = signaller;
			this.source = source;
			this.otherArbiter = new DeferredSubscription();
		}

		@Override
		public void cancel() {
			if (cancelled) {
				return;
			}
			cancelled = true;

			cancelWhen();

			super.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			set(s);
		}

		@Override
		public void onNext(T t) {
			subscriber.onNext(t);

			produced++;
		}

		@Override
		public void onError(Throwable t) {
			otherArbiter.cancel();

			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			long p = produced;
			produced = 0;
			produced(p);
			otherArbiter.request(1);
			signaller.onNext(p);
		}

		void cancelWhen() {
			otherArbiter.cancel();
		}

		void setWhen(Subscription w) {
			otherArbiter.set(w);
		}

		void resubscribe() {
			if (WIP.getAndIncrement(this) == 0) {
				do {
					if (cancelled) {
						return;
					}

					source.subscribe(this);

				} while (WIP.decrementAndGet(this) != 0);
			}
		}

		void whenError(Throwable e) {
			cancelled = true;
			super.cancel();

			subscriber.onError(e);
		}

		void whenComplete() {
			cancelled = true;
			super.cancel();

			subscriber.onComplete();
		}
	}

	static final class StreamRepeatWhenOtherSubscriber extends reactor.rx.Stream<Long>
			implements Subscriber<Object>, FeedbackLoop, Trace, Inner {
		StreamRepeatWhenMainSubscriber<?> main;

		final ProcessorEmitter<Long> completionSignal = new ProcessorEmitter<>();

		@Override
		public void onSubscribe(Subscription s) {
			main.setWhen(s);
		}

		@Override
		public void onNext(Object t) {
			main.resubscribe();
		}

		@Override
		public void onError(Throwable t) {
			main.whenError(t);
		}

		@Override
		public void onComplete() {
			main.whenComplete();
		}

		@Override
		public void subscribe(Subscriber<? super Long> s) {
			completionSignal.subscribe(s);
		}

		@Override
		public Object delegateInput() {
			return main;
		}

		@Override
		public Object delegateOutput() {
			return completionSignal;
		}
	}
}
