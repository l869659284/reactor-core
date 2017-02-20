/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Scannable;
import reactor.util.context.Context;

/**
 * Relays values from the main Publisher until another Publisher signals an event.
 *
 * @param <T> the value type of the main Publisher
 * @param <U> the value type of the other Publisher
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxTakeUntilOther<T, U> extends FluxOperator<T, T> {

	final Publisher<U> other;

	FluxTakeUntilOther(Flux<? extends T> source, Publisher<U> other) {
		super(source);
		this.other = Objects.requireNonNull(other, "other");
	}

	@Override
	public long getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super T> s, Context ctx) {
		TakeUntilMainSubscriber<T> mainSubscriber = new TakeUntilMainSubscriber<>(s);

		TakeUntilOtherSubscriber<U> otherSubscriber = new TakeUntilOtherSubscriber<>(mainSubscriber);

		other.subscribe(otherSubscriber);

		source.subscribe(mainSubscriber, ctx);
	}

	static final class TakeUntilOtherSubscriber<U> implements InnerConsumer<U> {
		final TakeUntilMainSubscriber<?> main;

		boolean once;

		TakeUntilOtherSubscriber(TakeUntilMainSubscriber<?> main) {
			this.main = main;
		}

		@Override
		public Context currentContext() {
			return main.currentContext();
		}

		@Override
		public Object scan(Attr key) {
			switch (key){
				case CANCELLED:
					return main.other == Operators.cancelledSubscription();
				case PARENT:
					return main.other;
				case ACTUAL:
					return main;
			}
			return null;
		}

		@Override
		public void onSubscribe(Subscription s) {
			main.setOther(s);

			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(U t) {
			onComplete();
		}

		@Override
		public void onError(Throwable t) {
			if (once) {
				return;
			}
			once = true;
			main.onError(t);
		}

		@Override
		public void onComplete() {
			if (once) {
				return;
			}
			once = true;
			main.onComplete();
		}
	}

	static final class TakeUntilMainSubscriber<T>
			extends CachedContextProducer<T>
			implements InnerOperator<T, T> {

		volatile Subscription       main;

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<TakeUntilMainSubscriber, Subscription> MAIN =
		  AtomicReferenceFieldUpdater.newUpdater(TakeUntilMainSubscriber.class, Subscription.class, "main");

		volatile Subscription other;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<TakeUntilMainSubscriber, Subscription> OTHER =
		  AtomicReferenceFieldUpdater.newUpdater(TakeUntilMainSubscriber.class, Subscription.class, "other");

		TakeUntilMainSubscriber(Subscriber<? super T> actual) {
			super(Operators.serialize(actual));
		}

		@Override
		public Object scan(Attr key) {
			switch (key){
				case PARENT:
					return main;
				case CANCELLED:
					return main == Operators.cancelledSubscription();
			}
			return super.scan(key);
		}

		@Override
		public Stream<? extends Scannable> inners() {
			return Stream.of(Scannable.from(other));
		}

		void setOther(Subscription s) {
			if (!OTHER.compareAndSet(this, null, s)) {
				s.cancel();
				if (other != Operators.cancelledSubscription()) {
					Operators.reportSubscriptionSet();
				}
			}
		}

		@Override
		public void request(long n) {
			main.request(n);
		}

		void cancelMain() {
			Subscription s = main;
			if (s != Operators.cancelledSubscription()) {
				s = MAIN.getAndSet(this, Operators.cancelledSubscription());
				if (s != null && s != Operators.cancelledSubscription()) {
					s.cancel();
				}
			}
		}

		void cancelOther() {
			Subscription s = other;
			if (s != Operators.cancelledSubscription()) {
				s = OTHER.getAndSet(this, Operators.cancelledSubscription());
				if (s != null && s != Operators.cancelledSubscription()) {
					s.cancel();
				}
			}
		}

		@Override
		public void cancel() {
			cancelMain();
			cancelOther();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (!MAIN.compareAndSet(this, null, s)) {
				s.cancel();
				if (main != Operators.cancelledSubscription()) {
					Operators.reportSubscriptionSet();
				}
			} else {
				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public void onError(Throwable t) {

			if (main == null) {
				if (MAIN.compareAndSet(this, null, Operators.cancelledSubscription())) {
					Operators.error(actual, t);
					return;
				}
			}
			cancel();

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (main == null) {
				if (MAIN.compareAndSet(this, null, Operators.cancelledSubscription())) {
					cancelOther();
					Operators.complete(actual);
					return;
				}
			}
			cancel();

			actual.onComplete();
		}
	}
}
