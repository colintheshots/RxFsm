package rxfsm;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import rx.Observable;
import rx.Subscription;
import rx.subscriptions.CompositeSubscription;

public class Fsm {

    private final String pathToInitialState;

	private State initialState; // Lazy construction, hence not final
    private Map<String, State> pathToStateMap; // Lazy construction, hence not final
    private Map<State, List<State>> stateAncestorMap; // Lazy construction, hence not final
    private CompositeSubscription transitionsSubscriptions; // Lazy construction, hence not final
    private final List<State> topStates;
	private State currentState;

    public static Fsm create() {
        return new Fsm(null, null);
    }


    public Fsm withInitialState(String pathToInitialState) {
        return new Fsm(pathToInitialState, topStates);
    }

    public Fsm withTopStates(State... topStates) {
        if (this.topStates != null) {
            throw new IllegalArgumentException("Top states can only be declared once");
        }

        return new Fsm(pathToInitialState, Arrays.asList(topStates));
    }

    public void activate() {
        if (topStates == null || topStates.isEmpty()) {
            throw new IllegalArgumentException("Top states needs to be provided");
        }

        this.pathToStateMap = generatePathToStateMap(topStates);
        this.stateAncestorMap = generateStateAncestorMap(topStates);
        this.initialState = pathToStateMap.get(pathToInitialState);
        this.transitionsSubscriptions = new CompositeSubscription();

        if (initialState == null) {
            throw new IllegalArgumentException("Initial state needs to be provided");
        }

        // TODO: Verify that the FSM is valid (e.g. all transition targets are valid states)
        enter(initialState);
    }

    private Fsm(String pathToInitialState, List<State> topStates) {
        this.pathToInitialState = pathToInitialState;
        this.pathToStateMap = null; //generatePathToStateMap(topStates);
        this.initialState = null; //pathToStateMap.get(pathToInitialState);
        this.currentState = null;
        this.transitionsSubscriptions = null;
        this.topStates = topStates;
        this.stateAncestorMap = null;
    }

    private void switchState(State targetState) {
        State actualTargetState = targetState;
        while (actualTargetState.getInitialSubState() != null) {
            actualTargetState = actualTargetState.getInitialSubState();
        }

        TransitionPath path =
                TransitionPathCalculator.calculateTransitionPath(
                        stateAncestorMap.get(currentState),
                        stateAncestorMap.get(actualTargetState));

        deactivateTransitions();
        exit(currentState);
        exitStates(path.getStatesToExit());
        enterStates(path.getStatesToEnter());
        enter(actualTargetState);
    }

	private void enter(State state) {
		state.enter();

        State initialSubState = state.getInitialSubState();
        if (initialSubState != null)
        {
            enter(initialSubState);
        }
        else
        {
            // The base case
            currentState = state;
            activateTransitions();
        }

	}

    private void exit(State state) {
        state.exit();
    }

    private void exitStates(List<State> statesToExit) {
        for (State s : statesToExit) {
            s.exit();
        }
    }

    private void enterStates(List<State> statesToEnter) {
        for (State s: statesToEnter) {
            s.enter();
        }
    }

    // http://stackoverflow.com/questions/27870136/java-lambda-stream-distinct-on-arbitrary-key
    private static <T> Predicate<T> distinctByKey(Function<? super T,Object> keyExtractor) {
        Map<Object,Boolean> seen = new HashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    private void activateTransitions() {
        List<Observable<String>> observableTransitions
                = generateObservableTransitionList(currentState, stateAncestorMap.get(currentState), currentState.getTransitions());

        if (!observableTransitions.isEmpty()) {
            Subscription s = Observable
                    .merge(observableTransitions)
                    .subscribe(pathToNewState -> {
                        // pathToNewState is null for internal transitions (by convention).
                        if (pathToNewState != null) {
                            switchState(pathToStateMap.get(pathToNewState));
                        }
                        else{
                            // Do nothing, for internal transitions this subscription is only here to
                            // enable actions to be executed when the transition triggers
                        }
                    });

            transitionsSubscriptions.add(s);
        }
    }

    private void deactivateTransitions() {
        transitionsSubscriptions.clear();
    }

    private static List<Observable<String>> generateObservableTransitionList(
            State sourceState, List<State> ancestors, List<Transition> transitions) {
        Stream<State> statesWhosTransitionsToActivate
                = Stream.concat(Stream.of(sourceState), ancestors.stream());

        List<Transition> toActivate
                = statesWhosTransitionsToActivate
                    .flatMap(state -> state.getTransitions().stream())
                    .collect(Collectors.toList());

        if (toActivate != null && !toActivate.isEmpty()) {
            List<Observable<String>> observableTransitions
                    = toActivate
                    .stream()
                    // Filter out those observables who's event is already handled by another observable.
                    // This is to handle "overriding" of event handling (ultimate hook pattern)
                    .filter(distinctByKey(transition -> transition.event()))
                    .map(transition -> transition.observable())
                    .collect(Collectors.toList());

            return observableTransitions;
        }
        else {
            return new ArrayList<Observable<String>>();
        }
    }

    // Generates a map where each state in the FSM is mapped against a list of its ancestors
    private static Map<State, List<State>> generateStateAncestorMap(List<State> topStates) {
        Map<State, List<State>> stateAncestorMap = new HashMap<State, List<State>>();
        for (State state: topStates) {
            stateAncestorMap.putAll(generateStateAncestorMap(state, new ArrayList<State>()));
        }

        return stateAncestorMap;
    }

    private static Map<State, List<State>> generateStateAncestorMap(State state, List<State> ancestors) {
        Map<State, List<State>> stateAncestorMap = new HashMap<State, List<State>>();
        stateAncestorMap.put(state, ancestors);

        List<State> subStateAncestors = new ArrayList<State>(ancestors);
        subStateAncestors.add(state);

        for (State subState: state.getSubStates())
        {
            stateAncestorMap.putAll(generateStateAncestorMap(subState, subStateAncestors));
        }

        return stateAncestorMap;
    }

    // Generates a where the path to each state in the FSM is the key and the corresponding state is the value
    private static Map<String, State> generatePathToStateMap(List<State> topStates) {
        Map<String, State> pathToStateMap = new HashMap<String, State>();
        for (State state: topStates) {
            pathToStateMap.putAll(generatePathToStateMap(state, "/" + state.getName()));
        }

        return pathToStateMap;
    }

    private static Map<String, State> generatePathToStateMap(State state, String pathToState) {
        Map<String, State> pathToStateMap = new HashMap<String, State>();
        pathToStateMap.put(pathToState, state);

        for (State subState: state.getSubStates())
        {
            pathToStateMap.putAll(generatePathToStateMap(subState, pathToState + "/" + subState.getName()));
        }

        return pathToStateMap;
    }
}
