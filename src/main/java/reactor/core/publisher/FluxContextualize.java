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
import java.util.function.BiFunction;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Fuseable;
import reactor.util.context.Context;
import reactor.util.context.ContextRelay;

final class FluxContextualize<T> extends FluxOperator<T, T> implements Fuseable {

	final BiFunction<Context, Context, Context> doOnContext;

	FluxContextualize(Flux<? extends T> source,
			BiFunction<Context, Context, Context> doOnContext) {
		super(source);
		this.doOnContext = Objects.requireNonNull(doOnContext, "doOnContext");
	}

	@Override
	public void subscribe(Subscriber<? super T> s, Context ctx) {
		Context c;

		try {
			c = doOnContext.apply(ctx, Context.empty());
		}
		catch (Throwable t) {
			Operators.error(s, Operators.onOperatorError(t));
			return;
		}
		if(c != ctx){
			ContextRelay.set(s, c);
		}
		source.subscribe(new ContextualizeSubscriber<>(s, doOnContext, c), c);
	}

	static final class ContextualizeSubscriber<T>
			implements ConditionalSubscriber<T>, InnerOperator<T, T>,
			           QueueSubscription<T> {

		final Subscriber<? super T>                 actual;
		final ConditionalSubscriber<? super T>      actualConditional;
		final BiFunction<Context, Context, Context> doOnContext;

		final Context context;

		QueueSubscription<T> qs;
		Subscription         s;

		@SuppressWarnings("unchecked")
		ContextualizeSubscriber(Subscriber<? super T> actual,
				BiFunction<Context, Context, Context> doOnContext,
				Context context) {
			this.actual = actual;
			this.context = context;
			this.doOnContext = doOnContext;
			if (actual instanceof ConditionalSubscriber) {
				this.actualConditional = (ConditionalSubscriber<? super T>) actual;
			}
			else {
				this.actualConditional = null;
			}
		}

		@Override
		public Object scan(Attr key) {
			switch (key) {
				case PARENT:
					return s;
			}
			return InnerOperator.super.scan(key);
		}

		@Override
		public void onContext(Context context) {
			Context c;
			try {
				c = doOnContext.apply(this.context, context);
			}
			catch (Throwable t) {
				actual.onError(Operators.onOperatorError(s, t));
				return;
			}
			if(c != this.context) {
				InnerOperator.super.onContext(c);
			}
		}

		@Override
		public Context currentContext() {
			return this.context;
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				if (s instanceof QueueSubscription) {
					this.qs = (QueueSubscription<T>) s;
				}

				actual.onSubscribe(this);
			}
		}

		@Override
		public void onNext(T t) {
			actual.onNext(t);
		}

		@Override
		public boolean tryOnNext(T t) {
			if (actualConditional != null) {
				return actualConditional.tryOnNext(t);
			}
			actual.onNext(t);
			return true;
		}

		@Override
		public void onError(Throwable t) {
			actual.onError(t);
		}

		@Override
		public void onComplete() {
			actual.onComplete();
		}

		@Override
		public Subscriber<? super T> actual() {
			return actual;
		}

		@Override
		public void request(long n) {
			s.request(n);
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public int requestFusion(int requestedMode) {
			if (qs == null) {
				return Fuseable.NONE;
			}
			return qs.requestFusion(requestedMode);
		}

		@Override
		public T poll() {
			if (qs != null) {
				return qs.poll();
			}
			return null;
		}

		@Override
		public boolean isEmpty() {
			return qs == null || qs.isEmpty();
		}

		@Override
		public void clear() {
			if (qs != null) {
				qs.clear();
			}
		}

		@Override
		public int size() {
			return qs != null ? qs.size() : 0;
		}
	}

}
