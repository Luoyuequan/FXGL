/*
 * The MIT License (MIT)
 *
 * FXGL - JavaFX Game Library
 *
 * Copyright (c) 2015 AlmasB (almaslvl@gmail.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.almasb.fxgl;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.almasb.fxgl.asset.AssetLoader;
import com.almasb.fxgl.asset.AudioPlayer;
import com.almasb.fxgl.asset.SaveLoadManager;
import com.almasb.fxgl.event.EventBus;
import com.almasb.fxgl.event.FXGLEvent;
import com.almasb.fxgl.event.MenuEvent;
import com.almasb.fxgl.input.Input;
import com.almasb.fxgl.donotuse.QTEManager;
import com.almasb.fxgl.event.UpdateEvent;
import com.almasb.fxgl.gameplay.AchievementManager;
import com.almasb.fxgl.physics.PhysicsWorld;
import com.almasb.fxgl.settings.*;
import com.almasb.fxgl.time.MasterTimer;
import com.almasb.fxgl.ui.*;
import com.almasb.fxgl.util.ExceptionHandler;
import com.almasb.fxgl.util.FXGLCheckedExceptionHandler;
import com.almasb.fxgl.util.FXGLLogger;
import com.almasb.fxgl.util.FXGLUncaughtExceptionHandler;
import com.almasb.fxgl.util.Version;

import com.google.inject.*;
import com.google.inject.name.Names;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * To use FXGL extend this class and implement necessary methods.
 * The initialization process can be seen below (irrelevant phases are omitted):
 * <p>
 * <ol>
 * <li>Instance fields of YOUR subclass of GameApplication</li>
 * <li>initSettings()</li>
 * <li>All FXGL managers (after this you can safely call get*Manager()</li>
 * <li>initInput()</li>
 * <li>initMenuFactory() (if enabled)</li>
 * <li>initIntroVideo() (if enabled)</li>
 * <li>initAssets()</li>
 * <li>initGame()</li>
 * <li>initPhysics()</li>
 * <li>initUI()</li>
 * <li>Start of main game loop execution</li>
 * </ol>
 * <p>
 * Unless explicitly stated, methods are not thread-safe and must be
 * executed on JavaFX Application Thread.
 *
 * @author Almas Baimagambetov (AlmasB) (almaslvl@gmail.com)
 */
public abstract class GameApplication extends Application {

    static {
        FXGLLogger.init(Level.CONFIG);
        Version.print();
    }

    /*
     * Order of execution.
     *
     * 1. the following initializer block (clinit)
     * 2. instance fields
     * 3. ctor()
     * 4. init()
     * 5. start()
     */ {
        // make sure first thing we do is get back the reference from JavaFX
        // so that anything can now use getInstance()
        log.finer("clinit()");
        setDefaultUncaughtExceptionHandler(new FXGLUncaughtExceptionHandler());
        setDefaultCheckedExceptionHandler(new FXGLCheckedExceptionHandler());
    }

    private static ExceptionHandler defaultCheckedExceptionHandler;

    /**
     * @return handler for checked exceptions
     */
    public static ExceptionHandler getDefaultCheckedExceptionHandler() {
        return defaultCheckedExceptionHandler;
    }

    /**
     * Set handler for checked exceptions
     *
     * @param handler exception handler
     */
    public static final void setDefaultCheckedExceptionHandler(ExceptionHandler handler) {
        defaultCheckedExceptionHandler = error -> {
            log.warning("Checked Exception:");
            log.warning(FXGLLogger.errorTraceAsString(error));
            handler.handle(error);
        };
    }

    /**
     * Set handler for runtime uncaught exceptions
     *
     * @param handler exception handler
     */
    public final void setDefaultUncaughtExceptionHandler(ExceptionHandler handler) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            pause();
            log.severe("Uncaught Exception:");
            log.severe(FXGLLogger.errorTraceAsString(error));
            log.severe("Application will now exit");
            handler.handle(error);
            exit();
        });
    }

    /**
     * The logger
     */
    protected static final Logger log = FXGLLogger.getLogger("FXGL.GameApplication");

    /**
     * Settings for this game instance. This is an internal copy
     * of the settings so that they will not be modified during game lifetime.
     */
    private ReadOnlyGameSettings settings;

    private GameState state;

    public GameState getState() {
        return state;
    }

    @Inject
    private Display display;
    @Inject
    private Input input;
    @Inject
    private AudioPlayer audioPlayer;

    private QTEManager qteManager;
    private SaveLoadManager saveLoadManager;
    private NotificationManager notificationManager;
    private AchievementManager achievementManager;

    /**
     * Initialize game settings.
     *
     * @param settings game settings
     */
    protected abstract void initSettings(GameSettings settings);

    /**
     * Override to register your achievements.
     *
     * <pre>
     * Example:
     *
     * AchievementManager am = getAchievementManager();
     * am.registerAchievement(new Achievement("Score Master", "Score 20000 points"));
     * </pre>
     */
    protected void initAchievements() {

    }

    /**
     * Initiliaze input, i.e.
     * bind key presses / key typed, bind mouse.
     * <p>
     * This method is called prior to any game init.
     * <p>
     * <pre>
     * Example:
     *
     * InputManager input = getInputManager();
     * input.addAction(new UserAction("Move Left") {
     *      protected void onAction() {
     *          player.translate(-5, 0);
     *      }
     * }, KeyCode.A);
     * </pre>
     */
    protected abstract void initInput();

    /**
     * Override to use your custom intro video.
     *
     * @return intro animation
     */
    protected IntroFactory initIntroFactory() {
        return new IntroFactory() {
            @Override
            public IntroScene newIntro() {
                return new FXGLIntroScene();
            }
        };
    }

    /**
     * Override to user your custom menus.
     *
     * @return menu factory for creating main and game menus
     */
    protected MenuFactory initMenuFactory() {
        return getSettings().getMenuStyle().getFactory();
    }

    /**
     * Initialize game assets, such as Texture, Sound, Music, etc.
     *
     * @throws Exception
     */
    protected abstract void initAssets() throws Exception;

    /**
     * Called when MenuEvent.SAVE occurs.
     * <p>
     * Default implementation returns null.
     *
     * @return data with required info about current state
     */
    public Serializable saveState() {
        log.warning("Called saveState(), but it wasn't overriden!");
        return null;
    }

    /**
     * Called when MenuEvent.LOAD occurs.
     *
     * @param data previously saved data
     */
    public void loadState(Serializable data) {
        log.warning("Called loadState(), but it wasn't overriden!");
    }

    /**
     * Initialize game objects.
     */
    protected abstract void initGame();

    /**
     * Initiliaze collision handlers, physics properties.
     */
    protected abstract void initPhysics();

    /**
     * Initiliaze UI objects.
     */
    protected abstract void initUI();

    /**
     * Main loop update phase, most of game logic.
     */
    protected abstract void onUpdate();

    /**
     * Default implementation does nothing.
     * <p>
     * This method is called during the transition from playing state
     * to menu state.
     */
    protected void onMenuOpen() {
    }

    /**
     * Default implementation does nothing.
     * <p>
     * This method is called during the transition from menu state
     * to playing state.
     */
    protected void onMenuClose() {
    }

    /**
     * Ensure managers are of legal state and ready.
     */
    private void initManagers() {
        saveLoadManager = SaveLoadManager.INSTANCE;
        //qteManager = new QTEManager();

        notificationManager = new NotificationManager(getGameScene().getRoot());
        achievementManager = new AchievementManager();

        // profile data listeners
        //profileSavables.add(inputManager);
        //profileSavables.add(audioManager);
        //profileSavables.add(sceneManager);
        //profileSavables.add(achievementManager);

        isMenuEnabled = getSettings().isMenuEnabled();
        menuOpen = new ReadOnlyBooleanWrapper(isMenuEnabled);

        getDisplay().registerScene(gameScene);
    }

    private static Injector injector;

    @SuppressWarnings("unchecked")
    private void configureServices(Stage stage) {
        injector = Guice.createInjector(new AbstractModule() {
            private Scene scene = new Scene(new Pane());

            @Override
            protected void configure() {
                for (Field field : ServiceType.class.getDeclaredFields()) {
                    try {
                        ServiceType type = (ServiceType) field.get(null);
                        if (type.service().equals(type.serviceProvider()))
                            bind(type.serviceProvider());
                        else
                            bind(type.service()).to(type.serviceProvider());
                    } catch (IllegalAccessException e) {
                        throw new IllegalArgumentException("Failed to configure services: " + e.getMessage());
                    }
                }

                requestStaticInjection(UIFactory.class);

                bind(Double.class)
                        .annotatedWith(Names.named("appWidth"))
                        .toInstance(getWidth());

                bind(Double.class)
                        .annotatedWith(Names.named("appHeight"))
                        .toInstance(getHeight());

                bind(ReadOnlyGameSettings.class).toInstance(getSettings());
            }

            @Provides
            GameApplication application() {
                return GameApplication.this;
            }

            @Provides
            Scene primaryScene() {
                return scene;
            }

            @Provides
            Stage primaryStage() {
                return stage;
            }
        });

        log.finer("Services configuration complete");

        injector.injectMembers(this);
    }

    private boolean canSwitchGameMenu = true;

    private void initEventHandlers() {
        getEventBus().addEventHandler(UpdateEvent.ANY, event -> onUpdate());
        getEventBus().addEventHandler(MenuEvent.NEW_GAME, event -> startNewGame());
        getEventBus().addEventHandler(MenuEvent.EXIT, event -> exit());
        getEventBus().addEventHandler(MenuEvent.EXIT_TO_MAIN_MENU, event -> {
            pause();
            reset();
        });

        getEventBus().addEventHandler(MenuEvent.PAUSE, event -> pause());
        getEventBus().addEventHandler(MenuEvent.RESUME, event -> resume());

        getEventBus().addEventHandler(MenuEvent.SAVE, event -> {
            String saveFileName = event.getData().map(name -> (String) name).orElse("");
            if (!saveFileName.isEmpty()) {
                boolean ok = getSaveLoadManager().save(saveState(), saveFileName).isOK();
//                if (!ok)
//                    getDisplay().showMessageBox("Failed to save");
            }
        });

        getEventBus().addEventHandler(MenuEvent.LOAD, event -> {
            String saveFileName = event.getData().map(name -> (String) name)
                    .orElse("");

            Optional<Serializable> saveFile = saveFileName.isEmpty()
                    ? getSaveLoadManager().loadLastModifiedFile()
                    : getSaveLoadManager().load(saveFileName);

            saveFile.ifPresent(this::startLoadedGame);
        });

        gameScene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (getSettings().isMenuEnabled()
                    && event.getCode() == getSettings().getMenuKey()
                    && getDisplay().getCurrentScene() == getGameScene()
                    && canSwitchGameMenu) {
                getEventBus().fireEvent(new MenuEvent(MenuEvent.PAUSE));
                canSwitchGameMenu = false;
            }
        });

        gameScene.addEventHandler(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == getSettings().getMenuKey()
                    && getDisplay().getCurrentScene() == getGameScene())
                canSwitchGameMenu = true;
        });
    }

    /**
     * Configure main stage based on user settings.
     *
     * @param stage the stage
     */
    private void initStage(Stage stage) {
        stage.setScene(injector.getInstance(Scene.class));
        stage.setTitle(settings.getTitle() + " " + settings.getVersion());
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> {
            e.consume();

            UIFactory.getDialogBox().showConfirmationBox("Exit the game?", yes -> {
                if (yes)
                    exit();
            });
        });
        stage.getIcons().add(getAssetLoader().loadAppIcon(settings.getIconFileName()));

        if (settings.isFullScreen()) {
            stage.setFullScreenExitHint("");
            // we don't want the user to be able to exit full screen manually
            // but only through settings menu
            // so we set key combination to something obscure which isn't likely
            // to be pressed
            stage.setFullScreenExitKeyCombination(KeyCombination.keyCombination("Shortcut+>"));
            stage.setFullScreen(true);
        }

        stage.sizeToScene();
    }

    /**
     * Game scene, this is where all in-game objects are shown.
     */
    @Inject
    private GameScene gameScene;

    /**
     * @return game scene
     */
    public GameScene getGameScene() {
        return gameScene;
    }

    /**
     * Intro scene, this is shown when the application started,
     * before menus and game.
     */
    private IntroScene introScene;

    /**
     * Main menu, this is the menu shown at the start of game
     */
    private FXGLScene mainMenuScene;

    /**
     * In-game menu, this is shown when menu key pressed during the game
     */
    private FXGLScene gameMenuScene;

    /**
     * Reference to current shown scene.
     */
    private FXGLScene currentScene;

    /**
     * Is menu enabled in settings
     */
    private boolean isMenuEnabled;

    private ReadOnlyBooleanWrapper menuOpen;

    /**
     * @return property tracking if any menu is open
     */
    public ReadOnlyBooleanProperty menuOpenProperty() {
        return menuOpen.getReadOnlyProperty();
    }

    /**
     * @return true if any menu is open
     */
    public boolean isMenuOpen() {
        return menuOpen.get();
    }

    /**
     * Changes current scene to given scene.
     *
     * @param scene the scene to set as active
     */
    private void setScene(FXGLScene scene) {
        getDisplay().setScene(scene);

        menuOpen.set(scene == mainMenuScene || scene == gameMenuScene);
    }

    /**
     * Creates Main and Game menu scenes.
     * Registers event handlers to menus.
     */
    private void configureMenu() {
        menuOpenProperty().addListener((obs, wasOpen, isOpen) -> {
            if (isOpen) {
                log.finer("Playing State -> Menu State");
                onMenuOpen();
            } else {
                log.finer("Menu State -> Playing State");
                onMenuClose();
            }
        });

        MenuFactory menuFactory = initMenuFactory();

        mainMenuScene = menuFactory.newMainMenu(this);
        gameMenuScene = menuFactory.newGameMenu(this);

        getDisplay().registerScene(mainMenuScene);
        getDisplay().registerScene(gameMenuScene);

        gameMenuScene.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == getSettings().getMenuKey()
                    && getDisplay().getCurrentScene() != getGameScene()
                    && canSwitchGameMenu) {
                getEventBus().fireEvent(new MenuEvent(MenuEvent.RESUME));
                canSwitchGameMenu = false;
            }
        });

        gameMenuScene.addEventHandler(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == getSettings().getMenuKey()
                    && getDisplay().getCurrentScene() != getGameScene())
                canSwitchGameMenu = true;
        });

        eventBus.addEventHandler(MenuEvent.PAUSE, event -> setScene(gameMenuScene));
        eventBus.addEventHandler(MenuEvent.RESUME, event -> setScene(gameScene));
        eventBus.addEventHandler(FXGLEvent.INIT_APP_COMPLETE, event -> setScene(gameScene));
        eventBus.addEventHandler(MenuEvent.EXIT_TO_MAIN_MENU, event -> setScene(mainMenuScene));
    }

    private void configureIntro() {
        introScene = initIntroFactory().newIntro();
        introScene.setOnFinished(this::showGame);
        getDisplay().registerScene(introScene);
    }

    /**
     * Called right before the main stage is shown.
     */
    void onStageShow() {
        if (isMenuEnabled)
            configureMenu();

        if (getSettings().isIntroEnabled()) {
            configureIntro();

            setScene(introScene);
            introScene.startIntro();
        } else {
            showGame();
        }
    }

    private void showGame() {
        if (isMenuEnabled) {
            setScene(mainMenuScene);
        } else {
            startNewGame();
            setScene(gameScene);
        }
    }

    @Override
    public final void start(Stage stage) throws Exception {
        log.finer("start()");

        GameSettings localSettings = new GameSettings();
        initSettings(localSettings);
        settings = localSettings.toReadOnly();

        Level logLevel = Level.ALL;
        switch (settings.getApplicationMode()) {
            case DEVELOPER:
                logLevel = Level.CONFIG;
                break;
            case RELEASE:
                logLevel = Level.SEVERE;
                break;
            case DEBUG: // fallthru
            default:
                break;
        }

        FXGLLogger.init(logLevel);

        configureServices(stage);

        UIFactory.init(getService(ServiceType.ASSET_LOADER).loadFont(settings.getDefaultFontName()));

        log.info("Application Mode: " + settings.getApplicationMode());

        initManagers();

        initAchievements();
        // we call this early to process user input bindings
        // so we can correctly display them in menus
        initInput();

        initEventHandlers();

        initStage(stage);

        defaultProfile = createProfile();
        SaveLoadManager.INSTANCE.loadProfile().ifPresent(this::loadFromProfile);

        onStageShow();
        stage.show();

        log.finer("Showing stage");
        log.finer("Root size: " + stage.getScene().getRoot().getLayoutBounds().getWidth() + "x" + stage.getScene().getRoot().getLayoutBounds().getHeight());
        log.finer("Scene size: " + stage.getScene().getWidth() + "x" + stage.getScene().getHeight());
        log.finer("Stage size: " + stage.getWidth() + "x" + stage.getHeight());
    }

    /**
     * Initialize user application.
     *
     * @param data the data to load from, null if new game
     */
    private void initApp(Serializable data) {
        log.finer("Initializing app");

        try {
            initAssets();

            if (data == null)
                initGame();
            else
                loadState(data);

            initPhysics();
            initUI();

            if (getSettings().isFPSShown()) {
                Text fpsText = UIFactory.newText("", 24);
                fpsText.setTranslateY(getSettings().getHeight() - 40);
                fpsText.textProperty().bind(getTimerManager().fpsProperty().asString("FPS: [%d]\n")
                        .concat(getTimerManager().performanceFPSProperty().asString("Performance: [%d]")));
                getGameScene().addUINode(fpsText);
            }

            getEventBus().fireEvent(FXGLEvent.initAppComplete());

        } catch (Exception e) {
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), e);
        }
    }

    /**
     * (Re-)initializes the user application as new and starts the game.
     */
    final void startNewGame() {
        log.finer("Starting new game");
        initApp(null);
    }

    /**
     * (Re-)initializes the user application from the given data file and starts the game.
     *
     * @param data save data to load from
     */
    final void startLoadedGame(Serializable data) {
        log.finer("Starting loaded game");
        reset();
        initApp(data);
    }

    /**
     * Pauses the main loop execution.
     */
    final void pause() {
        log.finer("Pausing main loop");
        getEventBus().fireEvent(FXGLEvent.pause());
    }

    /**
     * Resumes the main loop execution.
     */
    final void resume() {
        log.finer("Resuming main loop");
        getEventBus().fireEvent(FXGLEvent.resume());
    }

    /**
     * Reset the application.
     */
    final void reset() {
        log.finer("Resetting FXGL application");
        getEventBus().fireEvent(FXGLEvent.reset());
    }

    /**
     * Exits the application.
     * <p>
     * This method will be automatically called when main window is closed.
     */
    final void exit() {
        log.finer("Exiting Normally");
        getEventBus().fireEvent(FXGLEvent.exit());

        FXGLLogger.close();
        Platform.exit();
        System.exit(0);
    }

    private List<UserProfileSavable> profileSavables = new ArrayList<>();

    /**
     * Stores the default profile data. This is used to restore default settings.
     */
    private UserProfile defaultProfile;

    /**
     * Create a user profile with current settings.
     *
     * @return user profile
     */
    public final UserProfile createProfile() {
        UserProfile profile = new UserProfile(getSettings().getTitle(), getSettings().getVersion());
        profileSavables.forEach(s -> s.save(profile));
        return profile;
    }

    /**
     * Load from given user profile
     *
     * @param profile the profile
     */
    public final void loadFromProfile(UserProfile profile) {
        if (!profile.isCompatible(getSettings().getTitle(), getSettings().getVersion()))
            return;

        profileSavables.forEach(l -> l.load(profile));
    }

    /**
     * Load from default user profile. Restores default settings.
     */
    public final void loadFromDefaultProfile() {
        loadFromProfile(defaultProfile);
    }

    @Inject
    private GameWorld gameWorld;

    /**
     *
     * @return game world
     */
    public final GameWorld getGameWorld() {
        return gameWorld;
    }


    @Inject
    private PhysicsWorld physicsWorld;

    public final PhysicsWorld getPhysicsWorld() {
        return physicsWorld;
    }

    /**
     * Returns target width of the application. This is the
     * width that was set using GameSettings.
     * Note that the resulting
     * width of the scene might be different due to end user screen, in
     * which case transformations will be automatically scaled down
     * to ensure identical image on all screens.
     *
     * @return target width
     */
    public final double getWidth() {
        return getSettings().getWidth();
    }

    /**
     * Returns target height of the application. This is the
     * height that was set using GameSettings.
     * Note that the resulting
     * height of the scene might be different due to end user screen, in
     * which case transformations will be automatically scaled down
     * to ensure identical image on all screens.
     *
     * @return target height
     */
    public final double getHeight() {
        return getSettings().getHeight();
    }

    /**
     * Returns the visual area within the application window,
     * excluding window borders. Note that it will return the
     * rectangle with set target width and height, not actual
     * screen width and height. Meaning on smaller screens
     * the area will correctly return the GameSettings' width and height.
     * <p>
     * Equivalent to new Rectangle2D(0, 0, getWidth(), getHeight()).
     *
     * @return screen bounds
     */
    public final Rectangle2D getScreenBounds() {
        return new Rectangle2D(0, 0, getWidth(), getHeight());
    }

    @Inject
    private EventBus eventBus;

    public final EventBus getEventBus() {
        return eventBus;
    }

    /**
     * @return current tick
     */
    public final long getTick() {
        return getTimerManager().getTick();
    }

    /**
     * @return current time since start of game in nanoseconds
     */
    public final long getNow() {
        return getTimerManager().getNow();
    }

    /**
     * @return timer manager
     */
    public final MasterTimer getTimerManager() {
        return getService(ServiceType.MASTER_TIMER);
    }

    /**
     * @return scene manager
     */
    public final Display getDisplay() {
        return display;
    }

    /**
     * @return audio manager
     */
    public final AudioPlayer getAudioManager() {
        return audioPlayer;
    }

    /**
     * @return physics manager
     */
    public final PhysicsWorld getPhysicsManager() {
        return getPhysicsWorld();
    }

    /**
     * @return input manager
     */
    public final Input getInput() {
        return input;
    }

    /**
     * @return asset manager
     */
    public final AssetLoader getAssetLoader() {
        return getService(ServiceType.ASSET_LOADER);
    }

    /**
     * @return save load manager
     */
    public final SaveLoadManager getSaveLoadManager() {
        return saveLoadManager;
    }

    /**
     * @return QTE manager
     */
    public final QTEManager getQTEManager() {
        return qteManager;
    }

    /**
     *
     * @return notification manager
     */
    public final NotificationManager getNotificationManager() {
        return notificationManager;
    }

    /**
     *
     * @return achievement manager
     */
    public final AchievementManager getAchievementManager() {
        return achievementManager;
    }

    /**
     * @return read only copy of game settings
     */
    public final ReadOnlyGameSettings getSettings() {
        return settings;
    }

    public static <T> T getService(ServiceType<T> type) {
        return injector.getInstance(type.service());
    }
}
