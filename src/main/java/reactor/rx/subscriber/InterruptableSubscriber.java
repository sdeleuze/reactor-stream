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

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.ConsumerSubscriber;
import reactor.core.util.CancelledSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;
import reactor.fn.Consumer;
import reactor.fn.Function;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class InterruptableSubscriber<T> extends ConsumerSubscriber<T> implements Control {

	/**
	 *
	 * @param prefetch
	 * @param callback
	 * @param <T>
	 * @return
	 */
	public static <T> InterruptableSubscriber<T> bounded(int prefetch, Consumer<? super T> callback){
		return bounded(prefetch, callback, null, null);
	}

	/**
	 *
	 * @param prefetch
	 * @param callback
	 * @param errorCallback
	 * @param completeCallback
	 * @param <T>
	 * @return
	 */
	public static <T> InterruptableSubscriber<T> bounded(int prefetch, Consumer<? super T> callback,
			Consumer<? super Throwable> errorCallback, Runnable completeCallback){
		return new BoundedSubscriber<>(prefetch, callback, errorCallback, completeCallback);
	}

	/**
	 *
	 * @param consumer
	 * @param mapper
	 * @param broadcaster
	 * @param <O>
	 * @param <E>
	 * @return
	 */
	public static <O, E extends Processor<Long, Long>> InterruptableSubscriber<O> adaptive(Consumer<? super O> consumer,
			Function<? super Publisher<Long>, ? extends Publisher<? extends Long>> mapper,
			E broadcaster) {
		return new AdaptiveSubscriber<>(consumer, mapper, broadcaster);
	}

	/**
	 *
	 * @param stream
	 * @param consumer
	 * @param consumer1
	 * @param consumer2
	 * @param <O>
	 * @return
	 */
	public static <O> Demand bindLater(Publisher<O> stream,
			Consumer<? super O> consumer,
			Consumer<? super Throwable> consumer1,
			Runnable consumer2) {
		ManualSubscriber<O> manualSubscriber = new ManualSubscriber<>(consumer, consumer1, consumer2);
		stream.subscribe(manualSubscriber);
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

	@Override
	public ReactiveStateUtils.Graph debug() {
		return ReactiveStateUtils.scan(this);
	}

	@Override
	public boolean isStarted() {
		return SUBSCRIPTION.get(this) != EmptySubscription.INSTANCE ;
	}
}
