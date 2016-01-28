package reactor.rx;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.BackpressureUtils;


/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoIsEmpty<T> extends reactor.core.publisher.MonoSource<T, Boolean> {

	public MonoIsEmpty(Publisher<? extends T> source) {
		super(source);
	}

	@Override
	public void subscribe(Subscriber<? super Boolean> s) {
		source.subscribe(new IsEmptySubscriber<>(s));
	}

	static final class IsEmptySubscriber<T> extends DeferredScalarSubscriber<T, Boolean>
			implements Receiver {
		Subscription s;

		public IsEmptySubscriber(Subscriber<? super Boolean> actual) {
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
			s.cancel();

			complete(false);
		}

		@Override
		public void onComplete() {
			complete(true);
		}

		@Override
		public Object upstream() {
			return s;
		}
	}
}
