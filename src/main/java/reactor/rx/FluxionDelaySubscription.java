package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.DeferredSubscriptionSubscriber;
import reactor.core.util.BackpressureUtils;
import reactor.core.util.Exceptions;

/**
 * Delays the subscription to the main source until another Publisher
 * signals a value or completes.
 *
 * @param <T> the main source value type
 * @param <U> the other source type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class FluxionDelaySubscription<T, U> extends FluxionSource<T, T> {

	final Publisher<U> other;

	public FluxionDelaySubscription(Publisher<? extends T> source, Publisher<U> other) {
		super(source);
		this.other = Objects.requireNonNull(other, "other");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		other.subscribe(new DelaySubscriptionOtherSubscriber<>(s, source));
	}

	static final class DelaySubscriptionOtherSubscriber<T, U>
			extends DeferredSubscriptionSubscriber<U, T> {

		final Publisher<? extends T> source;

		Subscription s;

		boolean done;

		public DelaySubscriptionOtherSubscriber(Subscriber<? super T> actual, Publisher<? extends T> source) {
			super(actual);
			this.source = source;
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
		public void onNext(U t) {
			if (done) {
				return;
			}
			done = true;
			s.cancel();

			subscribeSource();
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

			subscribeSource();
		}

		void subscribeSource() {
			source.subscribe(new DelaySubscriptionMainSubscriber<>(subscriber, this));
		}

		static final class DelaySubscriptionMainSubscriber<T> implements Subscriber<T> {

			final Subscriber<? super T> actual;

			final DeferredSubscriptionSubscriber<?, ?> arbiter;

			public DelaySubscriptionMainSubscriber(Subscriber<? super T> actual,
															DeferredSubscriptionSubscriber<?, ?> arbiter) {
				this.actual = actual;
				this.arbiter = arbiter;
			}

			@Override
			public void onSubscribe(Subscription s) {
				arbiter.set(s);
			}

			@Override
			public void onNext(T t) {
				actual.onNext(t);
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
	}
}
