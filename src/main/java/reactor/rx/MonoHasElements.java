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
final class MonoHasElements<T> extends reactor.core.publisher.MonoSource<T, Boolean> {

	public MonoHasElements(Publisher<? extends T> source) {
		super(source);
	}

	@Override
	public void subscribe(Subscriber<? super Boolean> s) {
		source.subscribe(new HasElementsSubscriber<>(s));
	}

	static final class HasElementsSubscriber<T> extends DeferredScalarSubscriber<T, Boolean>
			implements Receiver {
		Subscription s;

		public HasElementsSubscriber(Subscriber<? super Boolean> actual) {
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

			complete(true);
		}

		@Override
		public void onComplete() {
			complete(false);
		}

		@Override
		public Object upstream() {
			return s;
		}
	}
}
