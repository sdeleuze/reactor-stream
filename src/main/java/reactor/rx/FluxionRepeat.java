package reactor.rx;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.MultiSubscriptionSubscriber;
import reactor.core.util.EmptySubscription;

/**
 * Repeatedly subscribes to the source and relays its values either
 * indefinitely or a fixed number of times.
 * <p>
 * The times == Long.MAX_VALUE is treated as infinite repeat.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class FluxionRepeat<T> extends FluxionSource<T, T> {

	final long times;

	public FluxionRepeat(Publisher<? extends T> source) {
		this(source, Long.MAX_VALUE);
	}

	public FluxionRepeat(Publisher<? extends T> source, long times) {
		super(source);
		if (times < 0L) {
			throw new IllegalArgumentException("times >= 0 required");
		}
		this.times = times;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		if (times == 0) {
			EmptySubscription.complete(s);
			return;
		}

		RepeatSubscriber<T> parent = new RepeatSubscriber<>(source, s, times);

		s.onSubscribe(parent);

		if (!parent.isCancelled()) {
			parent.onComplete();
		}
	}

	static final class RepeatSubscriber<T>
			extends MultiSubscriptionSubscriber<T, T> {

		final Publisher<? extends T> source;

		long remaining;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<RepeatSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(RepeatSubscriber.class, "wip");

		long produced;

		public RepeatSubscriber(Publisher<? extends T> source, Subscriber<? super T> actual, long remaining) {
			super(actual);
			this.source = source;
			this.remaining = remaining;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}

		@Override
		public void onComplete() {
			long r = remaining;
			if (r != Long.MAX_VALUE) {
				if (r == 0) {
					subscriber.onComplete();
					return;
				}
				remaining = r - 1;
			}

			resubscribe();
		}

		void resubscribe() {
			if (WIP.getAndIncrement(this) == 0) {
				do {
					if (isCancelled()) {
						return;
					}

					long c = produced;
					if (c != 0L) {
						produced = 0L;
						produced(c);
					}

					source.subscribe(this);

				} while (WIP.decrementAndGet(this) != 0);
			}
		}
	}
}
