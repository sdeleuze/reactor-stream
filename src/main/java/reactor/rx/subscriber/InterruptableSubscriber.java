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
package reactor.rx.subscriber;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.ConsumerSubscriber;
import reactor.core.util.CancelledSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;

/**
 * An extensible
 * {@link org.reactivestreams.Subscriber} that supports arbitrary thread-safe {@link Subscription} cancellation via {@link #run()}.
 * <p>The
 * {@link InterruptableSubscriber} also offers static factories completing {@link reactor.core.subscriber.Subscribers} available Reactor Core factories.
 * They include
 * {@link org.reactivestreams.Subscriber} generators for for prefetching {@link #bounded(int, Consumer)}, dynamically
 * requesting via
 * {@link #adaptive(Consumer, Function, Processor)} and manually requesting via {@link #bindLater(Publisher, Consumer, Consumer, Runnable)}.
 *
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consume.png" alt="">
 *
 * @param <T> the consumed sequence type
 *
 * @author Stephane Maldini
 * @since 2.5
 */
public class InterruptableSubscriber<T> extends ConsumerSubscriber<T> {

	/**
	 *
	 * Create a bounded {@link InterruptableSubscriber} that will keep a maximum in-flight items running.
	 * The prefetch strategy works with a first request N, then when 25% of N is left to be received on
	 * onNext, request N x 0.75.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consume.png" alt="">
	 *
	 * @param prefetch the in-flight capacity used to request source {@link Publisher}
	 * @param callback an onNext {@link Consumer} callback
	 * @param <T> the consumed sequence type
	 * @return a bounded {@link InterruptableSubscriber}
	 */
	public static <T> InterruptableSubscriber<T> bounded(int prefetch, Consumer<? super T> callback){
		return bounded(prefetch, callback, null, null);
	}

	/**
	 * Create a bounded {@link InterruptableSubscriber} that will keep a maximum in-flight items running.
	 * The prefetch strategy works with a first request N, then when 25% of N is left to be received on
	 * onNext, request N x 0.75.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consume.png" alt="">
	 *
	 * @param prefetch the in-flight capacity used to request source {@link Publisher}
	 * @param callback an onNext {@link Consumer} callback
	 * @param errorCallback an onError {@link Consumer} callback
	 * @param completeCallback an onComplete {@link Consumer} callback
	 * @param <T> the consumed sequence type
	 * @return a bounded {@link InterruptableSubscriber}
	 */
	public static <T> InterruptableSubscriber<T> bounded(int prefetch, Consumer<? super T> callback,
			Consumer<? super Throwable> errorCallback, Runnable completeCallback){
		return new BoundedSubscriber<>(prefetch, callback, errorCallback, completeCallback);
	}

	/**
	 * Create a {@link ManualSubscriber} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumelater.png" alt="">
	 *
	 * @param callback an onNext {@link Consumer} callback
	 * @param requestFactory the request flow factory
	 * @param broadcaster the {@link Processor} to use to publish and consume requests
	 * @param <O> the consumed sequence type
	 * @param <E> the {@link Processor} type
	 * @return an adaptive {@link InterruptableSubscriber}
	 */
	public static <O, E extends Processor<Long, Long>> InterruptableSubscriber<O> adaptive(Consumer<? super O> callback,
			Function<? super Publisher<Long>, ? extends Publisher<? extends Long>> requestFactory,
			E broadcaster) {
		return new AdaptiveSubscriber<>(callback, requestFactory, broadcaster);
	}

	/**
	 *
	 * Create a {@link ManualSubscriber} that will wait for interaction via {@link ManualSubscriber#request} to
	 * start consuming the sequence.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/consumelater.png" alt="">
	 *
	 * @param source the {@link Publisher} to subscribe to immediately
	 * @param callback an onNext {@link Consumer} callback
	 * @param errorCallback an onError {@link Consumer} callback
	 * @param completeCallback an onComplete {@link Consumer} callback
	 * @param <O> the consumed sequence type
	 *
	 * @return a new {@link ManualSubscriber}
	 */
	public static <O>  ManualSubscriber<O> bindLater(Publisher<O> source,
			Consumer<? super O> callback,
			Consumer<? super Throwable> errorCallback,
			Runnable completeCallback) {
		ManualSubscriber<O> manualSubscriber = new ManualSubscriber<>(callback, errorCallback, completeCallback);
		source.subscribe(manualSubscriber);
		return manualSubscriber;
	}

	@SuppressWarnings("unused")
	volatile Subscription subscription;

	final static AtomicReferenceFieldUpdater<InterruptableSubscriber, Subscription> SUBSCRIPTION =
			PlatformDependent.newAtomicReferenceFieldUpdater(InterruptableSubscriber.class, "subscription");

	public InterruptableSubscriber(Consumer<? super T> consumer, Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {
		super(consumer, errorConsumer, completeConsumer);
		SUBSCRIPTION.lazySet(this, EmptySubscription.INSTANCE);
	}

	@Override
	public void cancel() {
		if(SUBSCRIPTION.getAndSet(this, CancelledSubscription.INSTANCE) != CancelledSubscription.INSTANCE) {
			super.cancel();
		}
	}

	@Override
	protected final void doNext(T x) {
		if(subscription == CancelledSubscription.INSTANCE){
			Exceptions.onNextDropped(x);
		}
		super.doNext(x);
		doPostNext(x);
	}

	@Override
	protected final void doSubscribe(Subscription s) {
		if(SUBSCRIPTION.getAndSet(this, s) != CancelledSubscription.INSTANCE) {
			doSafeSubscribe(s);
		}
	}

	@Override
	protected final void doError(Throwable t) {
		if(SUBSCRIPTION.getAndSet(this, CancelledSubscription.INSTANCE) != CancelledSubscription.INSTANCE) {
			doSafeError(t);
		}
	}

	@Override
	protected final void doComplete() {
		if(SUBSCRIPTION.getAndSet(this, CancelledSubscription.INSTANCE) != CancelledSubscription.INSTANCE) {
			doSafeComplete();
		}
	}

	@Override
	protected void requestMore(long n) {
		Subscription subscription = SUBSCRIPTION.get(this);
		if(subscription != EmptySubscription.INSTANCE){
			subscription.request(n);
		}
	}

	protected void doSafeSubscribe(Subscription s){
		super.doSubscribe(s);
	}

	protected void doPostNext(T x) {

	}

	protected void doSafeComplete() {
		super.doComplete();
	}

	protected void doSafeError(Throwable t) {
		super.doError(t);
	}

	@Override
	public boolean isTerminated() {
		return SUBSCRIPTION.get(this) == CancelledSubscription.INSTANCE ;
	}

	/**
	 * Parse the materialized upstream source to fetch a materialized graph of the flow which allows for graph-style
	 * printing.
	 *
	 * @return a {@link ReactiveStateUtils} {@code Graph}
	 */
	public ReactiveStateUtils.Graph debug() {
		return ReactiveStateUtils.scan(this);
	}

	@Override
	public boolean isStarted() {
		return SUBSCRIPTION.get(this) != EmptySubscription.INSTANCE ;
	}
}
