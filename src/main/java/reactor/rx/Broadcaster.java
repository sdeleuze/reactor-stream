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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.SchedulerGroup;
import reactor.core.queue.QueueSupplier;
import reactor.core.state.Completable;
import reactor.core.state.Introspectable;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.CancelledSubscription;
import reactor.core.util.EmptySubscription;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;
import reactor.rx.subscriber.SerializedSubscriber;

/**
 * {@link Broadcaster} is an identity {@link Processor} extending {@link Stream} qualified for "Hot" sequence
 * generation. The message passing strategy can be chosen over the various factories including
 * {@link #async async}, {@link #replay replaying} or {@link #blocking blocking}.
 * <p>
 * A {@link Broadcaster} is similar to Reactive Extensions Subjects. Some broadcasters might be shared and will require
 * {@link #serialize serialization} as onXXXX handle should not be invoke concurrently. It is recommended to use the
 * safe {@link #startEmitter()} gateway to safely {@link SignalEmitter#emit(Object) emit} without failing
 * backpressure protocol. OnNext signals should not be invoked without demand and unpredicted behavior might occur in
 * this case.
 *
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcaster.png" alt="">
 *
 * <p>{@link #blocking()} and {@link #unicast()} are unicast restricted {@link Broadcaster} (at most one {@link Subscriber}.
 * Multicast operators like {@link #publish} or {@link #multicast} can however fan-out such limited {@link Broadcaster}.
 *
 * @param <O> the relayed type
 *
 * @author Stephane Maldini
 */
public class Broadcaster<O> extends StreamProcessor<O, O> {

	/**
	 * Create an
	 * {@link EmitterProcessor#create EmitterProcessor} that will be immediately composed with a {@link reactor.core.publisher.Flux#dispatchOn(Callable) dispatchOn}.
	 * It offers the same effect while providing for a
	 * {@link Stream} API instead of {@link reactor.core.publisher.Flux}.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterasync.png" alt="">
	 *
	 * @param <IN> the relayed type
	 *
	 * @return a new scheduled {@link Broadcaster}
	 */
	public static <IN> Broadcaster<IN> async(final SchedulerGroup group) {
		FluxProcessor<IN, IN> emitter = EmitterProcessor.create();
		return new Broadcaster<>(emitter, emitter.dispatchOn(group), false);
	}

	/**
	 * Create a unicast {@link FluxProcessor#blocking blocking} {@link Broadcaster} that will simply monitor the 
	 * requests downstream to gate each {@link #onNext(Object)} call. 
	 * 
	 * <p>Note that using this factory for synchronous 
	 * backpressured flow where {@code requests != Long.MAX_VALUE}, it could unexpectedly hang if the operator 
	 * use a post onNext request replenishment strategy.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterblocking.png" alt="">
	 *
	 * @param <IN> the relayed type
	 * 
	 * @return a blocking eventually interruptable {@link Broadcaster}
	 */
	public static <IN> Broadcaster<IN> blocking() {
		FluxProcessor<IN, IN> emitter = FluxProcessor.blocking();
		return new Broadcaster<>(emitter, emitter, true);
	}

	/**
	 * Create a {@link Broadcaster} from hot {@link EmitterProcessor} that will not propagate cancel upstream if 
	 * subscribed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcaster.png" alt="">
	 *
	 * @param <T> the relayed type
	 * 
	 * @return a non interruptable pub-sub {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create() {
		return create(false);
	}


	/**
	 * Create a {@link Broadcaster} from hot {@link EmitterProcessor#create EmitterProcessor} that will eventually 
	 * propagate cancel upstream
	 * if 
	 * subscribed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcaster.png" alt="">
	 *
	 * @param autoCancel Propagate cancel upstream
	 * @param <T> the relayed type
	 * 
	 * @return an eventually interruptable pub-sub {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> create(boolean autoCancel) {
		return new Broadcaster<T>(EmitterProcessor.<T>create(autoCancel), autoCancel);
	}

	/**
	 * Create a {@link Broadcaster} from hot-cold {@link EmitterProcessor#replay EmitterProcessor}  that will not 
	 * propagate 
	 * cancel upstream if {@link Subscription} has been set. Up to {@link PlatformDependent#SMALL_BUFFER_SIZE} history will be replayable to
	 * late {@link Subscriber}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterreplay.png" alt="">
	 *
	 * @param <T> the relayed type
	 * 
	 * @return an non interruptable caching {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replay() {
		return new Broadcaster<T>(EmitterProcessor.<T>replay(), false);
	}

	/**
	 * Create a {@link Broadcaster} from hot-cold {@link EmitterProcessor#replay EmitterProcessor}  that will not 
	 * propagate 
	 * cancel upstream if {@link Subscription} has been set. Up to a given history count will be replayable to
	 * late {@link Subscriber}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterreplay.png" alt="">
	 * @param history the maximum items to replay
	 * @param <T> the relayed type
	 * 
	 * @return an non interruptable caching {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replay(int history) {
		return new Broadcaster<T>(EmitterProcessor.<T>replay(history), false);
	}

	/**
	 * Create a {@link Broadcaster} from hot-cold {@link EmitterProcessor#replay EmitterProcessor}  that will not 
	 * propagate 
	 * cancel upstream if {@link Subscription} has been set. The last emitted item will be replayable to late {@link Subscriber} 
	 * (buffer and history size of 1).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterreplaylast.png" alt="">
	 *
	 * @param <T>  the relayed type
	 * 
	 * @return a non interruptable last item cached pub-sub {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replayLast() {
		return replayLastOrDefault(null);
	}

	/**
	 * Create a {@link Broadcaster} from hot-cold {@link EmitterProcessor#replay EmitterProcessor}  that will not 
	 * propagate 
	 * cancel upstream if {@link Subscription} has been set. The last emitted item will be replayable to late {@link Subscriber} (buffer and history size of 1).
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterreplaylastd.png" alt="">
	 *
	 * @param value a default value to start the sequence with
	 * @param <T> the relayed type
	 * 
	 * @return a non interruptable last item cached pub-sub {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> replayLastOrDefault(T value) {
		Broadcaster<T> b = new Broadcaster<T>(EmitterProcessor.<T>replay(1), false);
		if(value != null){
			b.onNext(value);
		}
		return b;
	}

	/**
	 * Create a 
	 * {@link Broadcaster} from hot {@link EmitterProcessor#create EmitterProcessor}  safely gated by {@link SerializedSubscriber}. 
	 * It will not propagate cancel upstream if {@link Subscription} has been set. Serialization uses thread-stealing
	 * and a potentially unbounded queue that might starve a calling thread if races are too important and
	 * {@link Subscriber} is slower.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterserialize.png" alt="">
	 *
	 * @param <T> the relayed type
	 * @return a serializing unicast {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> serialize() {
		Processor<T, T> processor = EmitterProcessor.create();
		return new Broadcaster<>(SerializedSubscriber.create(processor), processor, true);
	}

	/**
	 * Create an optimized {@link Broadcaster} from an internal optimized {@link Processor} for Unicasting. Usually
	 * used to bridge a simple hot source to a stream based flow where it should be preferred to {@link #create}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/broadcasterunicast.png" alt="">
	 *
	 * @param <T> the relayed type
	 * @return an eventually interruptable unicast {@link Broadcaster}
	 */
	public static <T> Broadcaster<T> unicast(){
		return new Broadcaster<>(new UnicastProcessor<>(QueueSupplier.<T>small(true).get()), true);
	}

	final boolean          ignoreDropped;
	final SwapSubscription subscription;
	protected Broadcaster(Processor<O, O> processor, boolean ignoreDropped) {
		this(processor, processor, ignoreDropped);
	}
	protected Broadcaster(
			Subscriber<O> receiver,
			Publisher<O> publisher,
			boolean ignoreDropped) {
		super(receiver, publisher);
		this.ignoreDropped = ignoreDropped;
		this.subscription = SwapSubscription.create();

		receiver.onSubscribe(subscription);
	}

	@Override
	public void onComplete() {
		try {
			receiver.onComplete();
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			//IGNORE
		}
	}

	@Override
	public void onError(Throwable t) {
		try {
			receiver.onError(t);
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			//IGNORE
		}
	}

	@Override
	public void onNext(O ev) {
		try {
			if(subscription.isCancelled()){
				Exceptions.onNextDropped(ev);
			}
			subscription.ack();
			receiver.onNext(ev);
		}
		catch (Exceptions.InsufficientCapacityException | Exceptions.CancelException c) {
			if (!ignoreDropped) {
				throw c;
			}
		}
	}

	@Override
	public void onSubscribe(Subscription subscription) {
		this.subscription.swapTo(subscription);
	}

	static final class SwapSubscription implements Subscription, Receiver, Completable, Introspectable {

		protected static final AtomicLongFieldUpdater<SwapSubscription> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(SwapSubscription.class, "requested");
		static final AtomicReferenceFieldUpdater<SwapSubscription, Subscription> SUBSCRIPTION =
				PlatformDependent.newAtomicReferenceFieldUpdater(SwapSubscription.class, "subscription");

		public static SwapSubscription create() {
			return new SwapSubscription();
		}
		@SuppressWarnings("unused")
		volatile Subscription subscription;
		@SuppressWarnings("unused")
		volatile long requested;

		SwapSubscription() {
			SUBSCRIPTION.lazySet(this, EmptySubscription.INSTANCE);
		}

		boolean ack(long l) {
			return BackpressureUtils.getAndSub(REQUESTED, this, l) >= l;
		}

		boolean ack(){
			return BackpressureUtils.getAndSub(REQUESTED, this, 1L) != 0;
		}

		@Override
		public void cancel() {
			Subscription s;
			for(;;) {
				s = subscription;
				if(s == CancelledSubscription.INSTANCE || s == EmptySubscription.INSTANCE){
					return;
				}

				if(SUBSCRIPTION.compareAndSet(this, s, CancelledSubscription.INSTANCE)){
					s.cancel();
					break;
				}
			}
		}

		@Override
		public int getMode() {
			return 0;
		}

		@Override
		public String getName() {
			return null;
		}

		boolean isCancelled(){
			return subscription == CancelledSubscription.INSTANCE;
		}

		@Override
		public boolean isStarted() {
			return !isUnsubscribed();
		}

		@Override
		public boolean isTerminated() {
			return isUnsubscribed();
		}

		boolean isUnsubscribed(){
			return subscription == EmptySubscription.INSTANCE;
		}

		@Override
		public void request(long n) {
			BackpressureUtils.getAndAdd(REQUESTED, this, n);
			SUBSCRIPTION.get(this)
			            .request(n);
		}

		@Override
		public String toString() {
			return "SwapSubscription{" +
					"subscription=" + subscription +
					", requested=" + requested +
					'}';
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		void swapTo(Subscription subscription) {
			Subscription old = SUBSCRIPTION.getAndSet(this, subscription);
			if(old != EmptySubscription.INSTANCE){
				subscription.cancel();
				return;
			}
			long r = REQUESTED.getAndSet(this, 0L);
			if(r != 0L){
				subscription.request(r);
			}
		}
	}
}
