package brotherdetjr.pauline.core;

import brotherdetjr.pauline.events.Event;
import brotherdetjr.pauline.events.EventSource;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import static brotherdetjr.utils.Utils.checkNotNull;
import static brotherdetjr.utils.Utils.searchInHierarchy;
import static com.google.common.base.Throwables.getStackTraceAsString;
import static com.google.common.collect.Maps.newConcurrentMap;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

public class Flow<Renderer, E extends Event> {
	private final EventSource<E> eventSource;
	private final Dispatcher<E> dispatcher;
	private final View<Renderer, E> failView;
	private final Executor executor;
	private final SessionStorage sessionStorage;
	private final Function<E, Renderer> rendererFactory;
	private final boolean allowUnlockedRendering;
	private final Logger log;

	public Flow(EventSource<E> eventSource,
				Dispatcher<E> dispatcher,
				View<Renderer, E> failView,
				Executor executor,
				SessionStorage sessionStorage,
				boolean allowUnlockedRendering,
				Function<E, Renderer> rendererFactory,
				Logger log) {
		this.eventSource = eventSource;
		this.dispatcher = dispatcher;
		this.failView = failView;
		this.executor = executor;
		this.sessionStorage = sessionStorage;
		this.rendererFactory = rendererFactory;
		this.allowUnlockedRendering = allowUnlockedRendering;
		this.log = log;
	}

	public void init() {
		eventSource.onEvent((event) -> {
			try {
				log.debug("Received event: {}", event);
				executor.execute(() -> process(event));
			} catch (Exception ex) {
				log.error("Failed to process event: {}. Cause: {}", event, getStackTraceAsString(ex));
				renderFailureNoLock(ex, event);
			}
		});
	}

	private void process(E event) {
		try {
			sessionStorage
				.acquireLock(event.getSessionId())
				.whenComplete((ignore, ex) -> onLockAcquired(event, ex));
		} catch (Exception ex) {
			log.error("Failed to acquire session lock. Event: {}. Cause: {}", event, getStackTraceAsString(ex));
			renderFailureNoLock(ex, event);
		}
	}

	private void onLockAcquired(E event, Throwable ex) {
		if (ex == null) {
			try {
				sessionStorage.getStateAndVars(event.getSessionId())
					.whenComplete((state, ex1) -> onStateAndVarsRetrieved(event, state, ex1));
			} catch (Exception ex1) {
				log.error("Failed to retrieve session state/vars. Event: {}. Cause: {}", event, ex1);
				renderFailureAndReleaseLock(ex1, event, null);
			}
		} else {
			log.error("Failed to acquire session lock. Event: {}. Cause: {}", event, getStackTraceAsString(ex));
			renderFailureNoLock(ex, event);
		}
	}

	private <T> void onStateAndVarsRetrieved(E event, Pair<T, Map<String, ?>> stateAndVars, Throwable ex) {
		if (ex == null) {
			Session<T> session = Session.of(event.getSessionId(), stateAndVars);
			try {
				dispatcher.dispatch(event, session).<Renderer>transit(event, session)
					.whenComplete((viewAndSession, ex1) -> onTransitionPerformed(event, viewAndSession, ex1));
			} catch (Exception ex1) {
				log.error("Failed to perform transition. Event: {}. Cause: {}", event, getStackTraceAsString(ex1));
				renderFailureAndReleaseLock(ex1, event, session);
			}
		} else {
			log.error("Failed to retrieve session state/vars. Event: {}. Cause: {}", event, ex);
			renderFailureAndReleaseLock(ex, event, null);
		}
	}

	private <To> void onTransitionPerformed(E event, ViewAndSession<To, Renderer, E> viewAndSession, Throwable ex) {
		if (ex == null) {
			try {
				Session<To> session = viewAndSession.getSession();
				sessionStorage.store(session.getState(), session.getVars())
					.whenComplete((ignore, ex1) -> onSessionStored(event, viewAndSession, ex1));
			} catch (Exception ex1) {
				log.error("Failed to store session. Event: {}. Cause: {}", event, getStackTraceAsString(ex1));
				renderFailureAndReleaseLock(ex1, event, viewAndSession.getSession());
			}
		} else {
			log.error("Failed to perform transition. Event: {}. Cause: {}", event, getStackTraceAsString(ex));
			renderFailureAndReleaseLock(ex, event, viewAndSession.getSession());
		}
	}

	private <To> void onSessionStored(E event, ViewAndSession<To, Renderer, E> viewAndSession, Throwable ex) {
		if (ex == null) {
			try {
				log.debug("Set new state for. Session ID: {}. State: {}",
					event.getSessionId(), viewAndSession.getSession().getState());
				viewAndSession.render(rendererFactory.apply(event), event)
					.whenComplete((ignore1, ex2) -> onViewRendered(event, viewAndSession, ex2));
			} catch (Exception ex1) {
				log.error("Failed to render view. Event: {}. Cause: {}", event, getStackTraceAsString(ex1));
				renderFailureAndReleaseLock(ex1, event, viewAndSession.getSession());
			}
		} else {
			log.error("Failed to store session. Event: {}. Cause: {}", event, getStackTraceAsString(ex));
			renderFailureAndReleaseLock(ex, event, viewAndSession.getSession());
		}
	}

	private <To> void onViewRendered(E event, ViewAndSession<To, Renderer, E> viewAndSession, Throwable ex) {
		if (ex == null) {
			releaseLock(event.getSessionId());
		} else {
			log.error("Failed to render view. Event: {}. Cause: {}", event, getStackTraceAsString(ex));
			renderFailureAndReleaseLock(ex, event, viewAndSession.getSession());
		}
	}

	private void renderFailureNoLock(Throwable ex, E event) {
		if (allowUnlockedRendering) {
			renderFailure(ex, event, null).whenComplete((ignore, ex1) -> {
				if (ex1 != null) {
					log.error("Failed to render: {}. Event: {}. Cause: {}",
						ex, event, getStackTraceAsString(ex1));
				}
			});
		}
	}

	private <From> void renderFailureAndReleaseLock(Throwable ex, E event, Session<From> session) {
		long sessionId = event.getSessionId();
		try {
			renderFailure(ex, event, session).whenComplete((ignore, ex1) -> {
				if (ex1 != null) {
					log.error("Failed to render: {}. Event: {}. Cause: {}",
						ex, event, getStackTraceAsString(ex1));
				}
				releaseLock(sessionId);
			});
		} catch (Exception ex1) {
			log.error("Failed to render: {}. Event: {}. Cause: {}",
				ex, event, getStackTraceAsString(ex1));
			releaseLock(sessionId);
		}
	}

	private <State> CompletableFuture<Void> renderFailure(Throwable ex, E event, Session<State> session) {
		return failView.render(View.Context.of(session, rendererFactory.apply(event), event, ex));
	}

	private void releaseLock(long sessionId) {
		log.debug("Releasing session lock. Session ID: {}", sessionId);
		sessionStorage.releaseLock(sessionId)
			.whenComplete((ignore, ex) -> {
				if (ex != null) {
					log.error("Failed to release session lock. Session ID: {}. Cause: {}",
						sessionId, getStackTraceAsString(ex));
				}
			});
	}

	@RequiredArgsConstructor
	public static class Builder<Renderer, E extends Event> {
		private EventSource<E> eventSource;
		private Map<Class<?>, View<?, Renderer, E>> views = newHashMap();
		private ControllerRegistry<E> controllers = new ControllerRegistry<>();
		private View<Throwable, Renderer, E> failView;
		private Executor executor = directExecutor();
		private Map<Long, Session> sessions = newConcurrentMap();
		private int stripes = 1000;
		private Function<E, Renderer> rendererFactory;
		private Logger log = LoggerFactory.getLogger(Flow.class);

		private Controller<?, ?, E> initial;

		@RequiredArgsConstructor
		public class Handle<E1 extends E> {
			private final Class<E1> eventClass;

			public <From> Builder<Renderer, E> with(BiFunction<E1, From, CompletableFuture<?>> func) {
				return new When<From>().with(func);
			}

			public Builder<Renderer, E> with(Function<E1, CompletableFuture<?>> func) {
				return with((event, ignore) -> func.apply(event));
			}

			public <From> Builder<Renderer, E> by(BiFunction<E1, From, CompletableFuture<?>> func) {
				return with(func);
			}

			public <From> When<From> when(From state) {
				return new When<>(state);
			}

			public <From> When<From> when(Class<From> stateClass) {
				return new When<>(stateClass);
			}

			public class When<From> {
				private final From state;
				private final Class<From> stateClass;

				public When(From state) {
					this.state = state;
					stateClass = null;
				}

				public When(Class<From> stateClass) {
					this.stateClass = stateClass;
					state = null;
				}

				public When() {
					state = null;
					stateClass = null;
				}

				@SuppressWarnings("unchecked")
				public <To> Builder<Renderer, E> with(BiFunction<E1, From, CompletableFuture<?>> func) {
					Controller<From, To, E1> controller = new Controller<From, To, E1>() {
						@Override
						public <R> CompletableFuture<ViewAndSession<To, R, E1>> transit(E1 e, From s) {
							return toViewAndState((CompletableFuture<To>) func.apply(e, s));
						}
					};
					Class<E1> eventClass = Handle.this.eventClass;
					if (state != null) {
						controllers.put(eventClass, state, controller);
					} else if (stateClass != null) {
						controllers.put(eventClass, stateClass, controller);
					} else {
						controllers.put(eventClass, controller);
					}
					return Builder.this;
				}

				public Builder<Renderer, E> with(Function<E1, CompletableFuture<?>> func) {
					return with((event, ignore) -> func.apply(event));
				}

				public Builder<Renderer, E> by(BiFunction<E1, From, CompletableFuture<?>> func) {
					return with(func);
				}

			}
		}

		@SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
		@RequiredArgsConstructor
		public class Render<State> {
			private final Collection<Class<? extends State>> keys;

			public Builder<Renderer, E> as(View<State, Renderer, E> view) {
				keys.forEach(key -> views.put(key, view));
				return Builder.this;
			}
		}

		public <E1 extends E> Handle<E1> handle(Class<E1> eventClass) {
			return new Handle<>(eventClass);
		}

		@SuppressWarnings("unchecked")
		public <E1 extends E> Handle<E1> handle() {
			return new Handle<>((Class<E1>) Event.class);
		}

		public <To> Builder<Renderer, E> initialController(Controller<Void, To, E> initial) {
			this.initial = initial;
			return this;
		}

		public <To> Builder<Renderer, E> initial(Function<E, CompletableFuture<To>> func) {
			return initialController(new Controller<Void, To, E>() {
				@Override
				public <R> CompletableFuture<ViewAndSession<To, R, E>> transit(E e, Void s) {
					return toViewAndState(func.apply(e));
				}
			});
		}

		public <To> Builder<Renderer, E> initial(
			Function<E, CompletableFuture<To>> func,
			@SuppressWarnings("UnusedParameters") Class<To> probe) {
			return initial(func);
		}

		@SafeVarargs
		public final <S> Render<S> render(Class<? extends S>... keys) {
			return new Render<>(asList(keys));
		}

		public Builder<Renderer, E> eventSource(EventSource<E> eventSource) {
			this.eventSource = eventSource;
			return this;
		}

		public Builder<Renderer, E> failView(View<Throwable, Renderer, E> failView) {
			this.failView = failView;
			return this;
		}

		public Builder<Renderer, E> executor(Executor executor) {
			this.executor = executor;
			return this;
		}

		@SuppressWarnings("unused")
		public Builder<Renderer, E> sessions(Map<Long, Session> sessions) {
			this.sessions = sessions;
			return this;
		}

		@SuppressWarnings("unused")
		public Builder<Renderer, E> stripes(int stripes) {
			this.stripes = stripes;
			return this;
		}

		public Builder<Renderer, E> rendererFactory(Function<E, Renderer> rendererFactory) {
			this.rendererFactory = rendererFactory;
			return this;
		}

		public Builder<Renderer, E> log(Logger log) {
			this.log = log;
			return this;
		}

		public Flow<Renderer, E> build(boolean initialized) {
			checkNotNull(rendererFactory, eventSource, initial, failView);
			Flow<Renderer, E> flow = new Flow<>(
				eventSource,
				newDispatcher(),
				failView,
				executor,
				sessions,
				stripes,
				rendererFactory,
				log
			);
			if (initialized) {
				flow.init();
			}
			return flow;
		}

		public Flow<Renderer, E> build() {
			return build(true);
		}

		private Dispatcher<E> newDispatcher() {
			return new Dispatcher<E>() {
				@SuppressWarnings("unchecked")
				@Override
				public <From, To> Controller<From, To, E> dispatch(E event, From state) {
					if (state != null) {
						return requireNonNull(
							controllers.get((Class<E>) event.getClass(), state),
							() -> "No controller registered for state " + state +
								" and event class " + event.getClass().getName()
						);
					} else {
						return (Controller<From, To, E>) initial;
					}
				}
			};
		}

		@SuppressWarnings("unchecked")
		private <To, R, E1 extends E> CompletableFuture<ViewAndSession<To, R, E1>> toViewAndState(
			CompletableFuture<To> future) {
			return future.thenApply(n ->
				ViewAndSession.of(
					ofNullable(
						searchInHierarchy(n.getClass(), c -> (View<To, R, E1>) views.get(c))
					).orElseThrow(() -> new IllegalStateException(
						"No view defined for state class " + n.getClass().getName())
					),
					n
				)
			);
		}
	}
}