package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;
import reactor.fn.Predicate;

/**
 * Emits a single boolean true if all values of the source sequence match
 * the predicate.
 * <p>
 * The implementation uses short-circuit logic and completes with false if
 * the predicate doesn't match a value.
 *
 * @param <T> the source value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoAll<T> extends reactor.core.publisher.MonoSource<T, Boolean> {

	final Predicate<? super T> predicate;

	public MonoAll(Publisher<? extends T> source, Predicate<? super T> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	@Override
	public void subscribe(Subscriber<? super Boolean> s) {
		source.subscribe(new AllSubscriber<T>(s, predicate));
	}

	static final class AllSubscriber<T> extends DeferredScalarSubscriber<T, Boolean>
			implements Receiver {
		final Predicate<? super T> predicate;

		Subscription s;

		boolean done;

		public AllSubscriber(Subscriber<? super Boolean> actual, Predicate<? super T> predicate) {
			super(actual);
			this.predicate = predicate;
		}

		@Override
		public void cancel() {
			s.cancel();
			super.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;
				subscriber.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {

			if (done) {
				return;
			}

			boolean b;

			try {
				b = predicate.test(t);
			} catch (Throwable e) {
				done = true;
				s.cancel();
				Exceptions.throwIfFatal(e);
				subscriber.onError(Exceptions.unwrap(e));
				return;
			}
			if (!b) {
				done = true;
				s.cancel();

				complete(false);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Exceptions.onErrorDropped(t);
				return;
			}
			done = true;

			subscriber.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			complete(true);
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object connectedInput() {
			return predicate;
		}

		@Override
		public boolean isTerminated() {
			return done;
		}
	}
}
