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
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscription.DeferredSubscription;
import reactor.core.subscription.EmptySubscription;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.Exceptions;
import reactor.fn.Supplier;
import reactor.rx.broadcast.UnicastProcessor;

/**
 * Splits the source sequence into continuous, non-overlapping windowEnds 
 * where the window boundary is signalled by another Publisher
 * 
 * @param <T> the input value type
 * @param <U> the boundary publisher's type (irrelevant)
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamWindowBoundary<T, U> extends StreamBarrier<T, reactor.rx.Stream<T>> {

	final Publisher<U> other;
	
	final Supplier<? extends Queue<T>> processorQueueSupplier;

	final Supplier<? extends Queue<Object>> drainQueueSupplier;

	public StreamWindowBoundary(Publisher<? extends T> source, Publisher<U> other, 
			Supplier<? extends Queue<T>> processorQueueSupplier,
					Supplier<? extends Queue<Object>> drainQueueSupplier) {
		super(source);
		this.other = Objects.requireNonNull(other, "other");
		this.processorQueueSupplier = Objects.requireNonNull(processorQueueSupplier, "processorQueueSupplier");
		this.drainQueueSupplier = Objects.requireNonNull(drainQueueSupplier, "drainQueueSupplier");
	}

	@Override
	public void subscribe(Subscriber<? super reactor.rx.Stream<T>> s) {

		Queue<T> q;
		
		try {
			q = processorQueueSupplier.get();
		} catch (Throwable e) {
			EmptySubscription.error(s, e);
			return;
		}

		if (q == null) {
			EmptySubscription.error(s, new NullPointerException("The processorQueueSupplier returned a null queue"));
			return;
		}
		
		Queue<Object> dq;
		
		try {
			dq = drainQueueSupplier.get();
		} catch (Throwable e) {
			EmptySubscription.error(s, e);
			return;
		}

		if (dq == null) {
			EmptySubscription.error(s, new NullPointerException("The drainQueueSupplier returned a null queue"));
			return;
		}

		StreamWindowBoundaryMain<T, U> main = new StreamWindowBoundaryMain<>(s, processorQueueSupplier, q, dq);
		
		s.onSubscribe(main);
		
		if (main.emit(main.window)) {
			other.subscribe(main.boundary);
			
			source.subscribe(main);
		}
	}
	
	static final class StreamWindowBoundaryMain<T, U>
	implements Subscriber<T>, Subscription, Runnable {
		
		final Subscriber<? super reactor.rx.Stream<T>> actual;

		final Supplier<? extends Queue<T>> processorQueueSupplier;
		
		final StreamWindowBoundaryOther<U> boundary;
		
		final Queue<Object> queue;
		
		UnicastProcessor<T> window;

		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<StreamWindowBoundaryMain, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, Subscription.class, "s");
		
		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<StreamWindowBoundaryMain> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, "requested");

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamWindowBoundaryMain> WIP =
				AtomicIntegerFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, "wip");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<StreamWindowBoundaryMain, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, Throwable.class, "error");
		
		volatile boolean cancelled;

		volatile int open;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamWindowBoundaryMain> OPEN =
				AtomicIntegerFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, "open");

		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamWindowBoundaryMain> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(StreamWindowBoundaryMain.class, "once");

		static final Object BOUNDARY_MARKER = new Object();
		
		public StreamWindowBoundaryMain(Subscriber<? super reactor.rx.Stream<T>> actual, 
				Supplier<? extends Queue<T>> processorQueueSupplier, 
						Queue<T> processorQueue, Queue<Object> queue) {
			this.actual = actual;
			this.processorQueueSupplier = processorQueueSupplier;
			this.window = new UnicastProcessor<>(processorQueue, this);
			this.open = 2;
			this.boundary = new StreamWindowBoundaryOther<>(this);
			this.queue = queue;
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}
		
		@Override
		public void onNext(T t) {
			synchronized (this) {
				queue.offer(t);
			}
			drain();
		}
		
		@Override
		public void onError(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				mainDone();
				drain();
			} else {
				Exceptions.onErrorDropped(t);
			}
		}
		
		@Override
		public void onComplete() {
			synchronized (this) {
				queue.offer(BOUNDARY_MARKER);
			}
			mainDone();
			drain();
		}
		
		@Override
		public void run() {
			if (OPEN.decrementAndGet(this) == 0) {
				cancelMain();
				boundary.cancel();
			}
		}
		
		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				BackpressureUtils.addAndGet(REQUESTED, this, n);
			}
		}
		
		void cancelMain() {
			BackpressureUtils.terminate(S, this);
		}

		void mainDone() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				run();
			}
		}
		
		@Override
		public void cancel() {
			cancelled = true;
			mainDone();
		}
		
		void boundaryNext() {
			synchronized (this) {
				queue.offer(BOUNDARY_MARKER);
			}

			if (cancelled) {
				boundary.cancel();
			}
			
			drain();
		}
		
		void boundaryError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				mainDone();
				drain();
			} else {
				Exceptions.onErrorDropped(e);
			}
		}
		
		void boundaryComplete() {
			synchronized (this) {
				queue.offer(BOUNDARY_MARKER);
			}
			mainDone();
			drain();
		}
		
		void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}
			
			final Subscriber<? super reactor.rx.Stream<T>> a = actual;
			final Queue<Object> q = queue;
			UnicastProcessor<T> w = window;
			
			int missed = 1;
			
			for (;;) {
				
				for (;;) {
					boolean d = open == 0 || error != null;
					
					Object o = q.poll();
					
					boolean empty = o == null;
					
					if (checkTerminated(d, empty, a, q, w)) {
						return;
					}
					
					if (empty) {
						break;
					}
					
					if (o == BOUNDARY_MARKER) {
						window = null;

						w.onComplete();
						
						if (!cancelled && open != 0 && error == null) {
							
							Queue<T> pq;

							try {
								pq = processorQueueSupplier.get();
							} catch (Throwable e) {
								emitError(a, e);
								return;
							}
							
							if (pq == null) {
								emitError(a, new NullPointerException("The processorQueueSupplier returned a null queue"));
								return;
							}
							
							OPEN.getAndIncrement(this);
							
							w = new UnicastProcessor<>(pq, this);
							
							long r = requested;
							if (r != 0L) {
								window = w;

								a.onNext(w);
								if (r != Long.MAX_VALUE) {
									REQUESTED.decrementAndGet(this);
								}
							} else {
								Throwable e = new IllegalStateException("Could not emit window due to lack of requests");

								emitError(a, e);
								return;
							}
						}
					} else {
						@SuppressWarnings("unchecked")
						T t = (T)o;
						w.onNext(t);
					}
				}
				
				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
		
		void emitError(Subscriber<?> a, Throwable e) {
			cancelMain();
			boundary.cancel();
			
			Exceptions.addThrowable(ERROR, this, e);
			e = Exceptions.terminate(ERROR, this);
			
			a.onError(e);
		}
		
		boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a, Queue<?> q, UnicastProcessor<?> w) {
			if (d) {
				Throwable e = Exceptions.terminate(ERROR, this);
				if (e != null && e != Exceptions.TERMINATED) {
					cancelMain();
					boundary.cancel();
					
					w.onError(e);
					
					a.onError(e);
					return true;
				} else
				if (empty) {
					cancelMain();
					boundary.cancel();

					w.onComplete();
					
					a.onComplete();
					return true;
				}
			}
			
			return false;
		}
		
		boolean emit(UnicastProcessor<T> w) {
			long r = requested;
			if (r != 0L) {
				actual.onNext(w);
				if (r != Long.MAX_VALUE) {
					REQUESTED.decrementAndGet(this);
				}
				return true;
			} else {
				cancel();
				
				actual.onError(new IllegalStateException("Could not emit buffer due to lack of requests"));

				return false;
			}
		}
	}
	
	static final class StreamWindowBoundaryOther<U>
			extends DeferredSubscription
	implements Subscriber<U> {
		
		final StreamWindowBoundaryMain<?, U> main;

		public StreamWindowBoundaryOther(StreamWindowBoundaryMain<?, U> main) {
			this.main = main;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(U t) {
			main.boundaryNext();
		}

		@Override
		public void onError(Throwable t) {
			main.boundaryError(t);
		}

		@Override
		public void onComplete() {
			main.boundaryComplete();
		}
	}
}
