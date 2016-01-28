package reactor.rx;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;

/**
 * Emits a scalar value if the source sequence turns out to be empty.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class StreamDefaultIfEmpty<T> extends StreamSource<T, T> {

	final T value;

	public StreamDefaultIfEmpty(Publisher<? extends T> source, T value) {
		super(source);
		this.value = Objects.requireNonNull(value, "value");
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new DefaultIfEmptySubscriber<>(s, value));
	}

	static final class DefaultIfEmptySubscriber<T>
			extends DeferredScalarSubscriber<T, T>
			implements Receiver {

		Subscription s;

		boolean hasValue;

		public DefaultIfEmptySubscriber(Subscriber<? super T> actual, T value) {
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
		public void setValue(T value) {
			// value is constant
		}

		@Override
		public Object upstream() {
			return s;
		}

		@Override
		public Object connectedInput() {
			return value;
		}
	}
}
