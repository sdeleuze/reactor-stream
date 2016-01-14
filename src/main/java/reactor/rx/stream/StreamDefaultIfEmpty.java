package reactor.rx.stream;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.SubscriberDeferredScalar;
import reactor.core.support.BackpressureUtils;

/**
 * Emits a scalar value if the source sequence turns out to be empty.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
public final class StreamDefaultIfEmpty<T> extends StreamBarrier<T, T> {

	final T value;

	public StreamDefaultIfEmpty(Publisher<? extends T> source, T value) {
		super(source);
		this.value = Objects.requireNonNull(value, "value");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new StreamDefaultIfEmptySubscriber<>(s, value));
	}

	static final class StreamDefaultIfEmptySubscriber<T>
			extends SubscriberDeferredScalar<T, T>
	implements Upstream{

		final T value;

		Subscription s;

		boolean hasValue;

		public StreamDefaultIfEmptySubscriber(Subscriber<? super T> actual, T value) {
			super(actual);
			this.value = value;
		}

		@Override
		public void request(long n) {
			super.request(n);
			s.request(n);
		}

		@Override
		public void cancel() {
			super.cancel();
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (BackpressureUtils.validate(this.s, s)) {
				this.s = s;

				subscriber.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			if (!hasValue) {
				hasValue = true;
			}

			subscriber.onNext(t);
		}

		@Override
		public void onComplete() {
			if (hasValue) {
				subscriber.onComplete();
			} else {
				complete(value);
			}
		}

		@Override
		public T get() {
			return value;
		}

		@Override
		public void setValue(T value) {
			// value is constant
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object delegateInput() {
			return value;
		}
	}
}
