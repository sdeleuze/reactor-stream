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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.util.PlatformDependent;

/**
 *  A
 *  {@link FluxionTap} provides a peek access into the last element visible of any sequence observed by the tap. Using a {@code Tap} one can
 * inspect the current event passing through a fluxion. A {@code StreamTap}'s value will be
 * continually updated as data passes through the fluxion, so a call to {@link #get()} will
 * return the last value seen by the event fluxion.
 *
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tap.png" alt="">
 *
 * @param <T> the type of values that this Tap can consume and supply
 * @author Stephane Maldini
 */
public class FluxionTap<T> extends FluxionSource<T, T> implements Supplier<T> {

	volatile T value;

	static final AtomicReferenceFieldUpdater<FluxionTap, Object> VAL =
			PlatformDependent.newAtomicReferenceFieldUpdater(FluxionTap.class, "value");

	/**
	 * Allow to continue assembling the chain while having access to {@link #get()} to peek the last observed item.
	 * If multiple subscribers are sharing the same {@link FluxionTap}, they will all participate in updating the
	 * observed tap.
	 *
	 * @param <T> the tapped type
	 * @return a new Tapping {@link FluxionTap}
	 */
	public static <T> FluxionTap<T> tap(Publisher<? extends T> source){
		if(source instanceof Fuseable){
			return new FluxionTapFuseable<>(source);
		}
		return new FluxionTap<>(source);
	}

	/**
	 * Create a {@code Tap}.
	 */
	FluxionTap(Publisher<? extends T> source) {
		super(source);
	}

	/**
	 * Get the value of this {@code Tap}, which is the current value of the event fluxion this
	 * tap is consuming.
	 *
	 * @return the value
	 */
	@Override
	@SuppressWarnings("unchecked")
	public T get() {
		return (T)VAL.get(this);
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new Tap<>(s, this));
	}

	static final class Tap<O> implements Subscriber<O>, Subscription, Receiver, Producer {

		final Subscriber<? super O> actual;
		final FluxionTap<O>         parent;
		Subscription s;

		public Tap(Subscriber<? super O> actual, FluxionTap<O> parent) {
			this.actual = actual;
			this.parent = parent;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.s = s;
			actual.onSubscribe(this);
		}

		@Override
		public void onNext(O o) {
			VAL.set(parent, o);
			actual.onNext(o);
		}

		@Override
		public void onError(Throwable t) {
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			actual.onComplete();
		}
	}

	static final class TapFuseable<O> extends Fuseable.SynchronousSubscription<O>
			implements Subscriber<O>, Receiver, Producer {
		final Subscriber<? super O> actual;
		final FluxionTap<O>         parent;

		Fuseable.QueueSubscription<O> s;
		int sourceMode;

		public TapFuseable(Subscriber<? super O> actual, FluxionTap<O> parent) {
			this.actual = actual;
			this.parent = parent;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onSubscribe(Subscription s) {
			this.s = (Fuseable.QueueSubscription<O>)s;
			actual.onSubscribe(this);
		}

		@Override
		public void onNext(O o) {
			VAL.set(parent, o);
			actual.onNext(o);
		}

		@Override
		public void onError(Throwable t) {
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			actual.onComplete();
		}

		@Override
		public void drop() {
			s.drop();
		}

		@Override
		public O poll() {
			O v = s.poll();
			if(v != null){
				VAL.set(parent, v);
			}
			return v;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public O peek() {
			O v = s.peek();
			if(v != null){
				VAL.set(parent, v);
			}
			return v;
		}

		@Override
		public int size() {
			return s.size();
		}

		@Override
		public boolean isEmpty() {
			return s.isEmpty();
		}

		@Override
		public void clear() {
			s.clear();
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public int requestFusion(int requestedMode) {
			int m = s.requestFusion(requestedMode);
			if (m != Fuseable.NONE) {
				sourceMode = m == Fuseable.SYNC ? Fuseable.SYNC : ((requestedMode & Fuseable.THREAD_BARRIER) != 0 ?
						Fuseable.NONE :
						Fuseable.ASYNC);
			}
			return m;
		}
	}

	static final class FluxionTapFuseable<O> extends FluxionTap<O> implements Fuseable {

		FluxionTapFuseable(Publisher<? extends O> source) {
			super(source);
		}

		@Override
		public void subscribe(Subscriber<? super O> s) {
			source.subscribe(new TapFuseable<>(s, this));
		}
	}
}
