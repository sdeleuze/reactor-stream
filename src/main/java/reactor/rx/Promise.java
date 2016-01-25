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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.core.timer.Timer;
import reactor.core.timer.Timers;
import reactor.core.trait.Cancellable;
import reactor.core.trait.Completable;
import reactor.core.trait.Failurable;
import reactor.core.trait.Introspectable;
import reactor.core.trait.Subscribable;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ScalarSubscription;
import reactor.fn.BiConsumer;
import reactor.fn.Consumer;
import reactor.fn.Supplier;

/**
 * A {@code Promise} is a stateful event container that accepts a single value or error. In addition to {@link #peek()
 * getting} or {@link #await() awaiting} the value, consumers can be registered to the outbound stream or via
 * , consumers can be registered to be notified of {@link #doOnError(Consumer) notified an error}, {@link
 * #doOnSuccess(Consumer) a value}, or {@link #doOnTerminate(BiConsumer)} both}. <p> A promise also provides methods for
 * composing actions with the future value much like a {@link Stream}. However, where a {@link
 * Stream} can process many values, a {@code Promise} processes only one value or error.
 *
 * @param <O> the type of the value that will be made available
 *
 * @author Jon Brisbin
 * @author Stephane Maldini
 * @see <a href="https://github.com/promises-aplus/promises-spec">Promises/A+ specification</a>
 */
public class Promise<O> extends Mono<O>
		implements Processor<O, O>, Consumer<O>, Subscription, Failurable, Completable, Cancellable, Subscribable {

	final static AtomicIntegerFieldUpdater<Promise>              STATE     =
			AtomicIntegerFieldUpdater.newUpdater(Promise.class, "state");
	final static AtomicIntegerFieldUpdater<Promise>              WIP       =
			AtomicIntegerFieldUpdater.newUpdater(Promise.class, "wip");
	final static AtomicIntegerFieldUpdater<Promise>              REQUESTED =
			AtomicIntegerFieldUpdater.newUpdater(Promise.class, "requested");
	final static AtomicReferenceFieldUpdater<Promise, Processor> PROCESSOR =
			PlatformDependent.newAtomicReferenceFieldUpdater(Promise.class, "processor");

	final static Promise   COMPLETE                = success(null);
	final static int       STATE_CANCELLED         = -1;
	final static int       STATE_READY             = 0;
	final static int       STATE_SUBSCRIBED        = 1;
	final static int       STATE_POST_SUBSCRIBED   = 2;
	final static int       STATE_SUCCESS_VALUE     = 3;
	final static int       STATE_COMPLETE_NO_VALUE = 4;
	final static int       STATE_ERROR             = 5;

	/**
	 * Create synchronous {@link Promise} and use the given error to complete the {@link Promise} immediately.
	 *
	 * @param error the error to complete the {@link Promise} with
	 * @param <T> the type of the value
	 *
	 * @return A {@link Promise} that is completed with the given error
	 */
	public static <T> Promise<T> error(Throwable error) {
		return error(Timers.globalOrNull(), error);
	}

	/**
	 * Create a {@link Promise} and use the given error to complete the {@link Promise} immediately.
	 *
	 * @param error the error to complete the {@link Promise} with
	 * @param timer the {@link Timer} to use by default for scheduled operations
	 * @param <T> the type of the value
	 *
	 * @return A {@link Promise} that is completed with the given error
	 */
	public static <T> Promise<T> error(Timer timer, Throwable error) {
		return new Promise<T>(error, timer);
	}

	/**
	 * Transform a publisher into a Promise thus subscribing to the passed source.
	 *
	 * @param source the data source
	 * @param <T> the type of the value
	 *
	 * @return A {@link Promise} that is completed with the given error
	 */
	@SuppressWarnings("unchecked")
	public static <T> Promise<T> from(Publisher<T> source) {
		if(source == null){
			return Promise.success(null);
		}
		if(Promise.class.isAssignableFrom(source.getClass())){
			return (Promise<T>)source;
		}
		if(Supplier.class.isAssignableFrom(source.getClass())){
			return success(((Supplier<T>)source).get());
		}
		Promise<T> p = Promise.prepare();
		source.subscribe(p);
		return p;
	}

	/**
	 * Create a synchronous {@link Promise}.
	 *
	 * @param <T> type of the expected value
	 *
	 * @return A {@link Promise}.
	 */
	public static <T> Promise<T> prepare() {
		return prepare(Timers.globalOrNull());
	}

	/**
	 * Create a `{@link Promise}.
	 *
	 * @param timer the {@link Timer} to use by default for scheduled operations
	 * @param <T> type of the expected value
	 *
	 * @return a new {@link Promise}
	 */
	public static <T> Promise<T> prepare(Timer timer) {
		Promise<T> p = new Promise<T>(timer);
		p.request(1L);
		return p;
	}

	/**
	 * Create a {@link Promise}.
	 *
	 * @param timer the {@link Timer} to use by default for scheduled operations
	 * @param <T> type of the expected value
	 *
	 * @return a new {@link Promise}
	 */
	public static <T> Promise<T> ready(Timer timer) {
		return new Promise<T>(timer);
	}

	/**
	 * Create a synchronous {@link Promise}.
	 *
	 * @param <T> type of the expected value
	 *
	 * @return A {@link Promise}.
	 */
	public static <T> Promise<T> ready() {
		return ready(Timers.globalOrNull());
	}

	/**
	 * Create a {@link Promise} already completed without any data.
	 *
	 * @return A {@link Promise} that is completed
	 */
	@SuppressWarnings("unchecked")
	public static Promise<Void> success() {
		return COMPLETE;
	}

	/**
	 * Create a {@link Promise} using the given value to complete the {@link Promise} immediately.
	 *
	 * @param value the value to complete the {@link Promise} with
	 * @param <T> the type of the value
	 *
	 * @return A {@link Promise} that is completed with the given value
	 */
	public static <T> Promise<T> success(T value) {
		return success(Timers.globalOrNull(), value);
	}

	/**
	 * Create a {@link Promise} using the given value to complete the {@link Promise} immediately.
	 *
	 * @param value the value to complete the {@link Promise} with
	 * @param timer the {@link Timer} to use by default for scheduled operations
	 * @param <T> the type of the value
	 *
	 * @return A {@link Promise} that is completed with the given value
	 */
	public static <T> Promise<T> success(Timer timer, T value) {
		if(value != null) {
			return new PromiseFulfilled<>(value, timer);
		}
		else{
			return new Promise<>(value, timer);
		}
	}

	final Timer        timer;
	final Publisher<O> source;

	Subscription subscription;

	volatile Processor<O, O> processor;
	volatile O               value;
	volatile Throwable       error;
	volatile int             state;
	volatile int             wip;
	volatile int             requested;


	/**
	 * Creates a new unfulfilled promise
	 *
	 * @param timer The default Timer for time-sensitive downstream actions if any.
	 */
	Promise(Timer timer) {
		this.timer = timer;
		this.source = null;
	}

	/**
	 * Creates a new unfulfilled promise
	 *
	 * @param timer The default Timer for time-sensitive downstream actions if any.
	 * @param source the optional source publisher
	 */
	Promise(Publisher<O> source, Timer timer) {
		this.timer = timer;
		this.source = source;
	}

	/**
	 * Creates a new promise that has been fulfilled with the given {@code value}.
	 *
	 * @param value The value that fulfills the promise
	 * @param timer The default Timer for time-sensitive downstream actions if any.
	 */
	Promise(O value, Timer timer) {
		this.timer = timer;
		this.value = value;
		this.source = null;
		STATE.lazySet(this, value == null ? STATE_COMPLETE_NO_VALUE : STATE_SUCCESS_VALUE);
	}

	/**
	 * Creates a new promise that has failed with the given {@code error}. <p> The {@code observable} is used when
	 * notifying the Promise's consumers, determining the thread on which they are called. If {@code env} is {@code
	 * null} the default await timeout will be 30 seconds.
	 *
	 * @param error The error the completed the promise
	 * @param timer The default Timer for time-sensitive downstream actions if any.
	 */
	Promise(Throwable error, Timer timer) {
		this.timer = timer;
		this.error = error;
		this.source = null;
		STATE.lazySet(this, STATE_ERROR);
	}

	@Override
	public void accept(O o) {
		onNext(o);
	}

	/**
	 * Block the calling thread, waiting for the completion of this {@code Promise}. A default timeout as specified in
	 * {@link System#getProperties()} using the key {@link PlatformDependent#DEFAULT_TIMEOUT} is used. The default is 30 seconds. If the
	 * promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return the value of this {@code Promise} or {@code null} if the timeout is reached and the {@code Promise} has
	 * not completed
	 *
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 * @throws RuntimeException     if the promise is completed with an error
	 */
	public final O await() throws InterruptedException {
		return await(PlatformDependent.DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code Promise}.
	 *
	 * @param timeout the timeout value
	 * @param unit the {@link TimeUnit} of the timeout value
	 *
	 * @return the value of this {@code Promise} or {@code null} if the timeout is reached and the {@code Promise} has
	 * not completed
	 *
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 */
	public final O await(long timeout, TimeUnit unit) throws InterruptedException {
		request(1);
		if (!isPending()) {
			return peek();
		}

		long delay = System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(timeout, unit);

		for (; ; ) {
			int endState = this.state;
				switch (endState) {
					case STATE_SUCCESS_VALUE:
						return value;
					case STATE_ERROR:
						if (error instanceof RuntimeException) {
							throw (RuntimeException) error;
						}
						Exceptions.fail(error);
					case STATE_COMPLETE_NO_VALUE:
						return null;
				}
			if (delay < System.currentTimeMillis()) {
				Exceptions.failWithCancel();
			}
			Thread.sleep(1);
		}
	}

	/**
	 * Block the calling thread, waiting for the completion of this {@code Promise}. A default timeout as specified in
	 * {@link System#getProperties()} using the key {@code reactor.await.defaultTimeout} is used. The default is 30
	 * seconds. If the promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return true if complete without error
	 *
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 * @throws RuntimeException     if the promise is completed with an error
	 */
	public final boolean awaitSuccess() throws InterruptedException {
		return awaitSuccess(PlatformDependent.DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code Promise}.
	 *
	 * @param timeout the timeout value
	 * @param unit the {@link TimeUnit} of the timeout value
	 *
	 * @return true if complete without error completed
	 *
	 * @throws InterruptedException if the thread is interruped while awaiting completion
	 */
	public final boolean awaitSuccess(long timeout, TimeUnit unit) throws InterruptedException {
		await(timeout, unit);
		return isSuccess();
	}

	@Override
	public final void cancel() {
		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				return;
			}
			if (STATE.compareAndSet(this, state, STATE_CANCELLED)) {
				break;
			}
			state = this.state;
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final Subscriber downstream() {
		return processor;
	}

	/**
	 * Block the calling thread for the specified time, waiting for the completion of this {@code Promise}. If the
	 * promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @param timeout the timeout value
	 * @param unit the {@link TimeUnit} of the timeout value
	 *
	 * @return the value of this {@code Promise} or {@code null} if the timeout is reached and the {@code Promise} has
	 * not completed
	 */
	@Override
	public O get(long timeout, TimeUnit unit) {
		try {
			return await(timeout, unit);
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();

			Exceptions.failWithCancel();
			return null;
		}
	}

	@Override
	public final Throwable getError() {
		return reason();
	}

	public final Timer getTimer() {
		return timer;
	}

	/**
	 * Indicates whether this {@code Promise} has been completed with an error.
	 *
	 * @return {@code true} if this {@code Promise} was completed with an error, {@code false} otherwise.
	 */
	public final boolean isError() {
		return state == STATE_ERROR;
	}

	/**
	 * Indicates whether this {@code Promise} has yet to be completed with a value or an error.
	 *
	 * @return {@code true} if this {@code Promise} is still pending, {@code false} otherwise.
	 *
	 * @see #isTerminated()
	 */
	public final boolean isPending() {
		return !isTerminated() && !isCancelled();
	}

	@Override
	public final boolean isStarted() {
		return state > STATE_READY && !isTerminated();
	}

	/**
	 * Indicates whether this {@code Promise} has been successfully completed a value.
	 *
	 * @return {@code true} if this {@code Promise} is successful, {@code false} otherwise.
	 */
	public final boolean isSuccess() {
		return state == STATE_COMPLETE_NO_VALUE || state == STATE_SUCCESS_VALUE;
	}

	@Override
	public final boolean isTerminated() {
		return state > STATE_POST_SUBSCRIBED;
	}

	@Override
	public final void onComplete() {
		onNext(null);
	}

	@Override
	public boolean isCancelled() {
		return state == STATE_CANCELLED;
	}

	@Override
	public final void onError(Throwable cause) {
		Subscription s = subscription;

		if ((source != null && s == null) || this.error != null) {
			Exceptions.onErrorDropped(cause);
			return;
		}

		this.error = cause;
		subscription = null;

		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				Exceptions.onErrorDropped(cause);
				return;
			}
			if (STATE.compareAndSet(this, state, STATE_ERROR)) {
				break;
			}
			state = this.state;
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final void onNext(O value) {
		Subscription s = subscription;

		if (value != null && ((source != null && s == null) || this.value != null)) {
			Exceptions.onNextDropped(value);
			return;
		}
		subscription = null;

		final int finalState;
		if(value != null) {
			finalState = STATE_SUCCESS_VALUE;
			this.value = value;
			if (s != null) {
				s.cancel();
			}
		}
		else {
			finalState = STATE_COMPLETE_NO_VALUE;
		}
		int state = this.state;
		for (; ; ) {
			if (state != STATE_READY && state != STATE_SUBSCRIBED && state != STATE_POST_SUBSCRIBED) {
				if(value != null) {
					Exceptions.onNextDropped(value);
				}
				return;
			}
			if (STATE.compareAndSet(this, state, finalState)) {
				break;
			}
			state = this.state;
		}


		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	public final void onSubscribe(Subscription subscription) {
		if (BackpressureUtils.validate(this.subscription, subscription)) {
			this.subscription = subscription;
			if (STATE.compareAndSet(this, STATE_READY, STATE_SUBSCRIBED) && REQUESTED.getAndSet(this, 2) != 2){
				subscription.request(1L);
			}

			if (WIP.getAndIncrement(this) == 0) {
				drainLoop();
			}
		}
	}

	/**
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public Stream<O> stream() {
		Processor<O, O> processor = this.processor;
		if (processor != NOOP_PROCESSOR && processor != null) {
			return Stream.from(processor);
		}
		int endState = this.state;
		if (endState == STATE_COMPLETE_NO_VALUE) {
			return Stream.empty();
		}
		else if (endState == STATE_SUCCESS_VALUE) {
			return Stream.just(value);
		}
		else if (endState == STATE_ERROR) {
			return Stream.fail(error);
		}

		Processor<O, O> out = processor;
		if (out == null) {
			out = Broadcaster.replayLastOrDefault(value, timer);
			if (PROCESSOR.compareAndSet(this, null, out)) {
				if (source != null) {
					source.subscribe(this);
				}
				else {
					out.onSubscribe(this);
				}
			}
			else {
				out = (Processor<O, O>) PROCESSOR.get(this);
			}
		}
		return Stream.from(out);
	}

	/**
	 * Returns the value that completed this promise. Returns {@code null} if the promise has not been completed. If the
	 * promise is completed with an error a RuntimeException that wraps the error is thrown.
	 *
	 * @return the value that completed the promise, or {@code null} if it has not been completed
	 *
	 * @throws RuntimeException if the promise was completed with an error
	 */
	public O peek() {
		request(1);
		int endState = this.state;

		if (endState == STATE_SUCCESS_VALUE) {
			return value;
		}
		else if (endState == STATE_ERROR) {
			if (RuntimeException.class.isInstance(error)) {
				throw (RuntimeException) error;
			}
			else {
				Exceptions.onErrorDropped(error);
				return null;
			}
		}
		else {
			return null;
		}
	}

	/**
	 * Peek the error (if any) that has completed this {@code Promise}. Returns {@code null} if the promise has not been
	 * completed, or was completed with a value.
	 *
	 * @return the error (if any)
	 */
	public final Throwable reason() {
		return error;
	}

	@Override
	public final void request(long n) {
		try {
			BackpressureUtils.checkRequest(n);
			Subscription s = subscription;
			if(!REQUESTED.compareAndSet(this, 0, 1) &&
				s != null && REQUESTED.compareAndSet(this, 1, 2)){
				s.request(1L);
			}
		}
		catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			onError(e);
		}
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(final Subscriber<? super O> subscriber) {
		int endState = this.state;
		if (endState == STATE_COMPLETE_NO_VALUE) {
			EmptySubscription.complete(subscriber);
			return;
		}
		else if (endState == STATE_SUCCESS_VALUE) {
			subscriber.onSubscribe(new ScalarSubscription<>(subscriber, value));
			return;
		}
		else if (endState == STATE_ERROR) {
			EmptySubscription.error(subscriber, error);
			return;
		}
		Processor<O, O> out = processor;
		if (out == null) {
			out = Broadcaster.replayLastOrDefault(value, timer);
			if (PROCESSOR.compareAndSet(this, null, out)) {
				if (source != null) {
					source.subscribe(this);
				}
			}
			else {
				out = (Processor<O, O>) PROCESSOR.get(this);
			}
		}
		out.subscribe(subscriber);
		if (WIP.getAndIncrement(this) == 0) {
			drainLoop();
		}
	}

	@SuppressWarnings("unchecked")
	final void drainLoop() {
		int missed = 1;

		int state;
		for (; ; ) {
			state = this.state;
			if (state > STATE_POST_SUBSCRIBED) {
				Processor<O, O> p = (Processor<O, O>) PROCESSOR.getAndSet(this, NOOP_PROCESSOR);
				if (p != NOOP_PROCESSOR && p != null) {
					switch (state) {
						case STATE_COMPLETE_NO_VALUE:
							p.onComplete();
							break;
						case STATE_SUCCESS_VALUE:
							p.onNext(value);
							p.onComplete();
							break;
						case STATE_ERROR:
							p.onError(error);
							break;
					}
					return;
				}
			}
			Subscription subscription = this.subscription;

			if(subscription != null) {
				if (state == STATE_CANCELLED && PROCESSOR.getAndSet(this, NOOP_PROCESSOR) != NOOP_PROCESSOR) {
					this.subscription = null;
					subscription.cancel();
					return;
				}

				if (REQUESTED.get(this) == 1 && REQUESTED.compareAndSet(this, 1, 2)) {
					subscription.request(1L);
				}
			}

			if (state == STATE_SUBSCRIBED && STATE.compareAndSet(this, STATE_SUBSCRIBED, STATE_POST_SUBSCRIBED)) {
				Processor<O, O> p = (Processor<O, O>) PROCESSOR.get(this);
				if (p != null && p != NOOP_PROCESSOR) {
					p.onSubscribe(this);
				}
			}

			missed = WIP.addAndGet(this, -missed);
			if (missed == 0) {
				break;
			}
		}
	}
	@Override
	public final String toString() {
		return "{" +
				"value : \"" + value + "\", " +
				"state : \"" + state + "\", " +
				"error : \"" + error + "\" " +
				'}';
	}

	@Override
	public int getMode() {
		return 0;
	}

	@Override
	public final Object upstream() {
		return subscription;
	}

	static final class PromiseFulfilled<T> extends Promise<T> implements Supplier<T> {

		final Stream<T> just;

		public PromiseFulfilled(T value, Timer timer) {
			super(value, timer);
			if (value != null) {
				just = Stream.just(value);
			}
			else {
				just = null;
			}
		}

		@Override
		public T get() {
			return value;
		}

		@Override
		public T peek() {
			return value;
		}

		@Override
		public Stream<T> stream() {
			return just == null ? Stream.<T>empty() : just;
		}

		@Override
		public void subscribe(Subscriber<? super T> subscriber) {
			subscriber.onSubscribe(new ScalarSubscription<>(subscriber, value));
		}
	}

	final static NoopProcessor NOOP_PROCESSOR = new NoopProcessor();

	final static class NoopProcessor implements Processor, Introspectable {

		@Override
		public void subscribe(Subscriber s) {

		}

		@Override
		public void onSubscribe(Subscription s) {

		}

		@Override
		public void onNext(Object o) {

		}

		@Override
		public void onError(Throwable t) {

		}

		@Override
		public void onComplete() {

		}

		@Override
		public int getMode() {
			return TRACE_ONLY;
		}

		@Override
		public String getName() {
			return NoopProcessor.class.getSimpleName();
		}
	}
}