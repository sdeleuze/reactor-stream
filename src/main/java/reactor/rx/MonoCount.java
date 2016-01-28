package reactor.rx;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;

/**
 * Counts the number of values in the source sequence.
 *
 * @param <T> the source value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoCount<T> extends reactor.core.publisher.MonoSource<T, Long> {

	public MonoCount(Publisher<? extends T> source) {
		super(source);
	}

	@Override
	public void subscribe(Subscriber<? super Long> s) {
		source.subscribe(new CountSubscriber<>(s));
	}

	static final class CountSubscriber<T> extends DeferredScalarSubscriber<T, Long>
			implements Receiver {

		long counter;

		Subscription s;

		public CountSubscriber(Subscriber<? super Long> actual) {
			super(actual);
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

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {
			counter++;
		}

		@Override
		public void onComplete() {
			complete(counter);
		}

		@Override
		public Object upstream() {
			return s;
		}

	}
}
