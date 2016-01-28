package reactor.rx;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.subscriber.MultiSubscriptionSubscriber;
import reactor.core.util.Exceptions;
import reactor.fn.BooleanSupplier;

/**
 * Repeatedly subscribes to the source if the predicate returns true after
 * completion of the previous subscription.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class StreamRepeatPredicate<T> extends StreamSource<T, T> {

	final BooleanSupplier predicate;

	public StreamRepeatPredicate(Publisher<? extends T> source, BooleanSupplier predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		RepeatPredicateSubscriber<T> parent = new RepeatPredicateSubscriber<>(source, s, predicate);

		s.onSubscribe(parent);

		if (!parent.isCancelled()) {
			parent.resubscribe();
		}
	}

	static final class RepeatPredicateSubscriber<T>
			extends MultiSubscriptionSubscriber<T, T> {

		final Publisher<? extends T> source;

		final BooleanSupplier predicate;

		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<RepeatPredicateSubscriber> WIP =
		  AtomicIntegerFieldUpdater.newUpdater(RepeatPredicateSubscriber.class, "wip");

		long produced;

		public RepeatPredicateSubscriber(Publisher<? extends T> source, 
				Subscriber<? super T> actual, BooleanSupplier predicate) {
			super(actual);
			this.source = source;
			this.predicate = predicate;
		}

		@Override
		public void onNext(T t) {
			produced++;

			subscriber.onNext(t);
		}

		@Override
		public void onComplete() {
			boolean b;
			
			try {
				b = predicate.getAsBoolean();
			} catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				subscriber.onError(Exceptions.unwrap(e));
				return;
			}
			
			if (b) {
				resubscribe();
			} else {
				subscriber.onComplete();
			}
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
