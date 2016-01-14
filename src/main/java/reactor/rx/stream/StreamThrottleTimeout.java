package reactor.rx.stream;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.Exceptions;
import reactor.core.subscription.DeferredSubscription;
import reactor.core.subscription.EmptySubscription;
import reactor.core.support.BackpressureUtils;
import reactor.fn.Function;
import reactor.fn.Supplier;

/**
 * Emits the last value from upstream only if there were no newer values emitted
 * during the time window provided by a publisher for that particular last value.
 *
 * @param <T> the source value type
 * @param <U> the value type of the duration publisher
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamThrottleTimeout<T, U> extends StreamBarrier<T, T> {

	final Function<? super T, ? extends Publisher<U>> throttler;
	
	final Supplier<Queue<Object>> queueSupplier;

	public StreamThrottleTimeout(Publisher<? extends T> source,
			Function<? super T, ? extends Publisher<U>> throttler,
					Supplier<Queue<Object>> queueSupplier) {
		super(source);
		this.throttler = Objects.requireNonNull(throttler, "throttler");
		this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void subscribe(Subscriber<? super T> s) {
		
		Queue<StreamThrottleTimeoutOther<T, U>> q;
		
		try {
			q = (Queue)queueSupplier.get();
		} catch (Throwable e) {
			EmptySubscription.error(s, e);
			return;
		}
		
		if (q == null) {
			EmptySubscription.error(s, new NullPointerException("The queueSupplier returned a null queue"));
			return;
		}
		
		StreamThrottleTimeoutMain<T, U> main = new StreamThrottleTimeoutMain<>(s, throttler, q);
		
		s.onSubscribe(main);
		
		source.subscribe(main);
	}
	
	static final class StreamThrottleTimeoutMain<T, U>
	implements Subscriber<T>, Subscription {
		
		final Subscriber<? super T> actual;
		
		final Function<? super T, ? extends Publisher<U>> throttler;
		
		final Queue<StreamThrottleTimeoutOther<T, U>> queue;

		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<StreamThrottleTimeoutMain, Subscription> S =
			AtomicReferenceFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, Subscription.class, "s");

		volatile Subscription other;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<StreamThrottleTimeoutMain, Subscription> OTHER =
			AtomicReferenceFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, Subscription.class, "other");

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<StreamThrottleTimeoutMain> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, "requested");

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamThrottleTimeoutMain> WIP =
				AtomicIntegerFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, "wip");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<StreamThrottleTimeoutMain, Throwable> ERROR =
			AtomicReferenceFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, Throwable.class, "error");

		volatile boolean done;
		
		volatile boolean cancelled;
		
		volatile long index;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<StreamThrottleTimeoutMain> INDEX =
				AtomicLongFieldUpdater.newUpdater(StreamThrottleTimeoutMain.class, "index");

		public StreamThrottleTimeoutMain(Subscriber<? super T> actual,
				Function<? super T, ? extends Publisher<U>> throttler,
						Queue<StreamThrottleTimeoutOther<T, U>> queue) {
			this.actual = actual;
			this.throttler = throttler;
			this.queue = queue;
		}

		@Override
		public void request(long n) {
			if (BackpressureUtils.validate(n)) {
				BackpressureUtils.addAndGet(REQUESTED, this, n);
			}
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				BackpressureUtils.terminate(S, this);
				BackpressureUtils.terminate(OTHER, this);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			long idx = INDEX.incrementAndGet(this);
			
			if (!BackpressureUtils.set(OTHER, this, EmptySubscription.INSTANCE)) {
				return;
			}
			
			Publisher<U> p;
			
			try {
				p = throttler.apply(t);
			} catch (Throwable e) {
				onError(e);
				return;
			}

			if (p == null) {
				onError(new NullPointerException("The throttler returned a null publisher"));
				return;
			}
			
			StreamThrottleTimeoutOther<T, U> os = new StreamThrottleTimeoutOther<>(this, t, idx);
			
			if (BackpressureUtils.replace(OTHER, this, os)) {
				p.subscribe(os);
			}
		}

		void error(Throwable t) {
			if (Exceptions.addThrowable(ERROR, this, t)) {
				done = true;
				drain();
			} else {
				Exceptions.onErrorDropped(t);
			}
		}
		
		@Override
		public void onError(Throwable t) {
			BackpressureUtils.terminate(OTHER, this);
			
			error(t);
		}

		@Override
		public void onComplete() {
			Subscription o = other;
			if (o instanceof StreamThrottleTimeoutOther) {
				StreamThrottleTimeoutOther<?, ?> os = (StreamThrottleTimeoutOther<?, ?>) o;
				os.cancel();
				os.onComplete();
			}
			done = true;
			drain();
		}
		
		void otherNext(StreamThrottleTimeoutOther<T, U> other) {
			queue.offer(other);
			drain();
		}
		
		void otherError(long idx, Throwable e) {
			if (idx == index) {
				BackpressureUtils.terminate(S, this);
				
				error(e);
			} else {
				Exceptions.onErrorDropped(e);
			}
		}
		
		void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}
			
			final Subscriber<? super T> a = actual;
			final Queue<StreamThrottleTimeoutOther<T, U>> q = queue;
			
			int missed = 1;
			
			for (;;) {
				
				for (;;) {
					boolean d = done;
					
					StreamThrottleTimeoutOther<T, U> o = q.poll();
					
					boolean empty = o == null;
					
					if (checkTerminated(d, empty, a, q)) {
						return;
					}
					
					if (empty) {
						break;
					}
					
					if (o.index == index) {
						long r = requested;
						if (r != 0) {
							a.onNext(o.value);
							if (r != Long.MAX_VALUE) {
								REQUESTED.decrementAndGet(this);
							}
						} else {
							cancel();
							
							q.clear();
							
							Throwable e = new IllegalStateException("Could not emit value due to lack of requests");
							Exceptions.addThrowable(ERROR, this, e);
							e = Exceptions.terminate(ERROR, this);
							
							a.onError(e);
							return;
						}
					}
				}
				
				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					break;
				}
			}
		}
		
		boolean checkTerminated(boolean d, boolean empty, Subscriber<?> a, Queue<?> q) {
			if (cancelled) {
				q.clear();
				return true;
			}
			if (d) {
				Throwable e = Exceptions.terminate(ERROR, this);
				if (e != null && e != Exceptions.TERMINATED) {
					cancel();
					
					q.clear();
					
					a.onError(e);
					return true;
				} else
				if (empty) {
					
					a.onComplete();
					return true;
				}
			}
			return false;
		}
	}
	
	static final class StreamThrottleTimeoutOther<T, U>
	extends DeferredSubscription
	implements Subscriber<U> {
		final StreamThrottleTimeoutMain<T, U> main;
		
		final T value;
		
		final long index;
		
		volatile int once;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<StreamThrottleTimeoutOther> ONCE =
				AtomicIntegerFieldUpdater.newUpdater(StreamThrottleTimeoutOther.class, "once");
		

		public StreamThrottleTimeoutOther(StreamThrottleTimeoutMain<T, U> main, T value, long index) {
			this.main = main;
			this.value = value;
			this.index = index;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (set(s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(U t) {
			if (ONCE.compareAndSet(this, 0, 1)) {
				cancel();
				
				main.otherNext(this);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (ONCE.compareAndSet(this, 0, 1)) {
				main.otherError(index, t);
			} else {
				Exceptions.onErrorDropped(t);
			}
		}

		@Override
		public void onComplete() {
			if (ONCE.compareAndSet(this, 0, 1)) {
				main.otherNext(this);
			}
		}
	}
}
