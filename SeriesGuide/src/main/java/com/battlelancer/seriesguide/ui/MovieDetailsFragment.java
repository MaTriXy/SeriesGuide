package com.battlelancer.seriesguide.ui;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.Loader;
import android.support.v4.graphics.ColorUtils;
import android.support.v4.util.SparseArrayCompat;
import android.support.v4.widget.NestedScrollView;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.graphics.Palette;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.api.Action;
import com.battlelancer.seriesguide.backend.HexagonTools;
import com.battlelancer.seriesguide.databinding.FragmentMovieBinding;
import com.battlelancer.seriesguide.databinding.RatingsMoviesBinding;
import com.battlelancer.seriesguide.extensions.ActionsHelper;
import com.battlelancer.seriesguide.extensions.ExtensionManager;
import com.battlelancer.seriesguide.extensions.MovieActionsContract;
import com.battlelancer.seriesguide.items.MovieDetails;
import com.battlelancer.seriesguide.loaders.MovieCreditsLoader;
import com.battlelancer.seriesguide.loaders.MovieLoader;
import com.battlelancer.seriesguide.loaders.MovieTrailersLoader;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.settings.TmdbSettings;
import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.battlelancer.seriesguide.ui.dialogs.LanguageChoiceDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.MovieCheckInDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.RateDialogFragment;
import com.battlelancer.seriesguide.util.LanguageTools;
import com.battlelancer.seriesguide.util.MovieTools;
import com.battlelancer.seriesguide.util.PeopleListHelper;
import com.battlelancer.seriesguide.util.ServiceUtils;
import com.battlelancer.seriesguide.util.ShareUtils;
import com.battlelancer.seriesguide.util.TimeTools;
import com.battlelancer.seriesguide.util.TmdbTools;
import com.battlelancer.seriesguide.util.TraktTools;
import com.battlelancer.seriesguide.util.Utils;
import com.readystatesoftware.systembartint.SystemBarTintManager;
import com.squareup.picasso.Callback;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.androidutils.CheatSheet;
import com.uwetrottmann.tmdb2.entities.Credits;
import com.uwetrottmann.tmdb2.entities.Movie;
import com.uwetrottmann.tmdb2.entities.Videos;
import com.uwetrottmann.trakt5.entities.Ratings;
import java.util.ArrayList;
import java.util.List;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import timber.log.Timber;

/**
 * Displays details about one movie including plot, ratings, trailers and a poster.
 */
public class MovieDetailsFragment extends Fragment implements MovieActionsContract {

    public static MovieDetailsFragment newInstance(int tmdbId) {
        MovieDetailsFragment f = new MovieDetailsFragment();

        Bundle args = new Bundle();
        args.putInt(InitBundle.TMDB_ID, tmdbId);
        f.setArguments(args);

        return f;
    }

    public interface InitBundle {

        String TMDB_ID = "tmdbid";
    }

    private static final String TAG = "Movie Details";

    private FragmentMovieBinding binding;

    private int tmdbId;
    private MovieDetails movieDetails = new MovieDetails();
    private Videos.Video trailer;
    private int currentLanguageIndex;
    private Handler handler = new Handler();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_movie, container, false);

        binding.progressBar.setVisibility(View.VISIBLE);
        binding.textViewMovieGenresLabel.setVisibility(View.GONE);

        // important action buttons
        binding.movieButtons.containerMovieButtons.setVisibility(View.GONE);
        binding.movieRatings.containerRatings.setVisibility(View.GONE);

        // language button
        binding.buttonMovieLanguage.setVisibility(View.GONE);
        Utils.setVectorCompoundDrawable(getActivity().getTheme(), binding.buttonMovieLanguage,
                R.attr.drawableLanguage);
        CheatSheet.setup(binding.buttonMovieLanguage, R.string.pref_language);
        binding.buttonMovieLanguage.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                DialogFragment dialog = LanguageChoiceDialogFragment.newInstance(
                        currentLanguageIndex);
                dialog.show(getFragmentManager(), "dialog-language");
            }
        });

        // comments button
        binding.buttonMovieComments.setVisibility(View.GONE);
        Utils.setVectorCompoundDrawable(getActivity().getTheme(), binding.buttonMovieComments,
                R.attr.drawableComments);

        // cast and crew
        setCastVisibility(false);
        setCrewVisibility(false);

        return binding.getRoot();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        tmdbId = getArguments().getInt(InitBundle.TMDB_ID);
        if (tmdbId <= 0) {
            getFragmentManager().popBackStack();
            return;
        }

        setupViews();

        Bundle args = new Bundle();
        args.putInt(InitBundle.TMDB_ID, tmdbId);
        getLoaderManager().initLoader(MovieDetailsActivity.LOADER_ID_MOVIE, args,
                mMovieLoaderCallbacks);
        getLoaderManager().initLoader(MovieDetailsActivity.LOADER_ID_MOVIE_TRAILERS, args,
                mMovieTrailerLoaderCallbacks);
        getLoaderManager().initLoader(MovieDetailsActivity.LOADER_ID_MOVIE_CREDITS, args,
                mMovieCreditsLoaderCallbacks);

        setHasOptionsMenu(true);
    }

    private void setupViews() {
        final int decorationHeightPx;
        if (AndroidUtils.isKitKatOrHigher()) {
            // avoid overlap with status + action bar (adjust top margin)
            // warning: status bar not always translucent (e.g. Nexus 10)
            // (using fitsSystemWindows would not work correctly with multiple views)
            SystemBarTintManager.SystemBarConfig config
                    = ((MovieDetailsActivity) getActivity()).getSystemBarTintManager().getConfig();
            int pixelInsetTop = config.getPixelInsetTop(false);

            // action bar height is pre-set as top margin, add to it
            decorationHeightPx = pixelInsetTop + binding.contentContainerMovie.getPaddingTop();
            binding.contentContainerMovie.setPadding(0, decorationHeightPx, 0, 0);

            // dual pane layout?
            if (binding.contentContainerMovieRight != null) {
                binding.contentContainerMovieRight.setPadding(0, decorationHeightPx, 0, 0);
            }
        } else {
            // content container has actionBarSize top padding by default
            decorationHeightPx = binding.contentContainerMovie.getPaddingTop();
        }

        // show toolbar title and background when scrolling
        final int defaultPaddingPx = getResources().getDimensionPixelSize(R.dimen.default_padding);
        NestedScrollView.OnScrollChangeListener scrollChangeListener
                = new ToolbarScrollChangeListener(defaultPaddingPx, decorationHeightPx);
        binding.contentContainerMovie.setOnScrollChangeListener(scrollChangeListener);
        if (binding.contentContainerMovieRight != null) {
            binding.contentContainerMovieRight.setOnScrollChangeListener(scrollChangeListener);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        BaseNavDrawerActivity.ServiceActiveEvent event = EventBus.getDefault()
                .getStickyEvent(BaseNavDrawerActivity.ServiceActiveEvent.class);
        setMovieButtonsEnabled(event == null);

        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        // refresh actions when returning, enabled extensions or their actions might have changed
        loadMovieActionsDelayed();
    }

    @Override
    public void onStop() {
        super.onStop();

        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        if (movieDetails != null) {
            // choose theme variant
            boolean isLightTheme = SeriesGuidePreferences.THEME == R.style.Theme_SeriesGuide_Light;
            inflater.inflate(
                    isLightTheme ? R.menu.movie_details_menu_light : R.menu.movie_details_menu,
                    menu);

            // enable/disable actions
            boolean isEnableShare = movieDetails.tmdbMovie() != null && !TextUtils.isEmpty(
                    movieDetails.tmdbMovie().title);
            MenuItem shareItem = menu.findItem(R.id.menu_movie_share);
            shareItem.setEnabled(isEnableShare);
            shareItem.setVisible(isEnableShare);

            boolean isEnableImdb = movieDetails.tmdbMovie() != null
                    && !TextUtils.isEmpty(movieDetails.tmdbMovie().imdb_id);
            MenuItem imdbItem = menu.findItem(R.id.menu_open_imdb);
            imdbItem.setEnabled(isEnableImdb);
            imdbItem.setVisible(isEnableImdb);

            boolean isEnableYoutube = trailer != null;
            MenuItem youtubeItem = menu.findItem(R.id.menu_open_youtube);
            youtubeItem.setEnabled(isEnableYoutube);
            youtubeItem.setVisible(isEnableYoutube);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_movie_share) {
            ShareUtils.shareMovie(getActivity(), tmdbId, movieDetails.tmdbMovie().title);
            Utils.trackAction(getActivity(), TAG, "Share");
            return true;
        }
        if (itemId == R.id.menu_open_imdb) {
            ServiceUtils.openImdb(movieDetails.tmdbMovie().imdb_id, TAG, getActivity());
            return true;
        }
        if (itemId == R.id.menu_open_youtube) {
            ServiceUtils.openYoutube(trailer.key, TAG, getActivity());
            return true;
        }
        if (itemId == R.id.menu_open_tmdb) {
            TmdbTools.openTmdbMovie(getActivity(), tmdbId, TAG);
        }
        if (itemId == R.id.menu_open_trakt) {
            Utils.launchWebsite(getActivity(), TraktTools.buildMovieUrl(tmdbId), TAG, "trakt");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void populateMovieViews() {
        /**
         * Get everything from TMDb. Also get additional rating from trakt.
         */
        final Ratings traktRatings = movieDetails.traktRatings();
        final Movie tmdbMovie = movieDetails.tmdbMovie();
        final boolean inCollection = movieDetails.inCollection;
        final boolean inWatchlist = movieDetails.inWatchlist;
        final boolean isWatched = movieDetails.isWatched;
        final int rating = movieDetails.userRating;

        binding.textViewMovieTitle.setText(tmdbMovie.title);
        getActivity().setTitle(tmdbMovie.title);
        binding.textViewMovieDescription.setText(tmdbMovie.overview);

        // release date and runtime: "July 17, 2009 | 95 min"
        StringBuilder releaseAndRuntime = new StringBuilder();
        if (tmdbMovie.release_date != null) {
            releaseAndRuntime.append(
                    TimeTools.formatToLocalDate(getContext(), tmdbMovie.release_date));
            releaseAndRuntime.append(" | ");
        }
        releaseAndRuntime.append(
                getString(R.string.runtime_minutes, String.valueOf(tmdbMovie.runtime)));
        binding.textViewMovieDate.setText(releaseAndRuntime.toString());

        // check-in button
        final String title = tmdbMovie.title;
        Button checkinButton = binding.movieButtons.buttonMovieCheckIn;
        checkinButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // display a check-in dialog
                MovieCheckInDialogFragment f = MovieCheckInDialogFragment
                        .newInstance(tmdbId, title);
                f.show(getFragmentManager(), "checkin-dialog");
                Utils.trackAction(getActivity(), TAG, "Check-In");
            }
        });
        CheatSheet.setup(checkinButton);

        // hide check-in if not connected to trakt or hexagon is enabled
        boolean isConnectedToTrakt = TraktCredentials.get(getActivity()).hasCredentials();
        boolean displayCheckIn = isConnectedToTrakt && !HexagonTools.isSignedIn(getActivity());
        checkinButton.setVisibility(displayCheckIn ? View.VISIBLE : View.GONE);
        binding.movieButtons.dividerMovieButtons.setVisibility(
                displayCheckIn ? View.VISIBLE : View.GONE);

        // watched button (only supported when connected to trakt)
        Button watchedButton = binding.movieButtons.buttonMovieWatched;
        if (isConnectedToTrakt) {
            watchedButton.setText(isWatched ? R.string.action_unwatched : R.string.action_watched);
            CheatSheet.setup(watchedButton,
                    isWatched ? R.string.action_unwatched : R.string.action_watched);
            Utils.setCompoundDrawablesRelativeWithIntrinsicBounds(watchedButton, 0, isWatched
                    ? Utils.resolveAttributeToResourceId(getActivity().getTheme(),
                    R.attr.drawableWatched)
                    : Utils.resolveAttributeToResourceId(getActivity().getTheme(),
                            R.attr.drawableWatch), 0, 0);
            watchedButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isWatched) {
                        MovieTools.unwatchedMovie(SgApp.from(getActivity()), tmdbId);
                        Utils.trackAction(getActivity(), TAG, "Unwatched movie");
                    } else {
                        MovieTools.watchedMovie(SgApp.from(getActivity()), tmdbId);
                        Utils.trackAction(getActivity(), TAG, "Watched movie");
                    }
                }
            });
            watchedButton.setVisibility(View.VISIBLE);
        } else {
            watchedButton.setVisibility(View.GONE);
        }

        // collected button
        Button collectedButton = binding.movieButtons.buttonMovieCollected;
        Utils.setCompoundDrawablesRelativeWithIntrinsicBounds(collectedButton, 0,
                inCollection
                        ? R.drawable.ic_collected
                        : Utils.resolveAttributeToResourceId(getActivity().getTheme(),
                                R.attr.drawableCollect), 0, 0);
        collectedButton.setText(inCollection ? R.string.action_collection_remove
                : R.string.action_collection_add);
        CheatSheet.setup(collectedButton, inCollection ? R.string.action_collection_remove
                : R.string.action_collection_add);
        collectedButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (inCollection) {
                    MovieTools.removeFromCollection(SgApp.from(getActivity()), tmdbId);
                    Utils.trackAction(getActivity(), TAG, "Uncollected movie");
                } else {
                    MovieTools.addToCollection(SgApp.from(getActivity()), tmdbId);
                    Utils.trackAction(getActivity(), TAG, "Collected movie");
                }
            }
        });

        // watchlist button
        Button watchlistedButton = binding.movieButtons.buttonMovieWatchlisted;
        Utils.setCompoundDrawablesRelativeWithIntrinsicBounds(watchlistedButton, 0,
                inWatchlist
                        ? R.drawable.ic_listed
                        : Utils.resolveAttributeToResourceId(getActivity().getTheme(),
                                R.attr.drawableList), 0, 0);
        watchlistedButton.setText(
                inWatchlist ? R.string.watchlist_remove : R.string.watchlist_add);
        CheatSheet.setup(watchlistedButton,
                inWatchlist ? R.string.watchlist_remove : R.string.watchlist_add);
        watchlistedButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (inWatchlist) {
                    MovieTools.removeFromWatchlist(SgApp.from(getActivity()), tmdbId);
                    Utils.trackAction(getActivity(), TAG, "Unwatchlist movie");
                } else {
                    MovieTools.addToWatchlist(SgApp.from(getActivity()), tmdbId);
                    Utils.trackAction(getActivity(), TAG, "Watchlist movie");
                }
            }
        });

        // show button bar
        binding.movieButtons.containerMovieButtons.setVisibility(View.VISIBLE);

        // language button
        LanguageTools.LanguageData languageData = LanguageTools.getMovieLanguageData(getContext());
        if (languageData != null) {
            currentLanguageIndex = languageData.languageIndex;
            binding.buttonMovieLanguage.setText(languageData.languageString);
        } else {
            binding.buttonMovieLanguage.setText(null);
        }
        binding.buttonMovieLanguage.setVisibility(View.VISIBLE);

        // ratings
        RatingsMoviesBinding ratings = binding.movieRatings;
        ratings.textViewRatingsTmdbValue.setText(
                TraktTools.buildRatingString(tmdbMovie.vote_average));
        ratings.textViewRatingsTmdbVotes.setText(
                TraktTools.buildRatingVotesString(getActivity(), tmdbMovie.vote_count));
        if (traktRatings != null) {
            ratings.textViewRatingsTraktVotes.setText(
                    TraktTools.buildRatingVotesString(getActivity(), traktRatings.votes));
            ratings.textViewRatingsTraktValue.setText(
                    TraktTools.buildRatingString(traktRatings.rating));
        }
        // if movie is not in database, can't handle user ratings
        RelativeLayout ratingsContainer = binding.movieRatings.containerRatings;
        if (!inCollection && !inWatchlist && !isWatched) {
            ratings.textViewRatingsTraktUserLabel.setVisibility(View.GONE);
            ratings.textViewRatingsTraktUser.setVisibility(View.GONE);
            ratingsContainer.setClickable(false);
            ratingsContainer.setLongClickable(false); // cheat sheet
        } else {
            ratings.textViewRatingsTraktUserLabel.setVisibility(View.VISIBLE);
            ratings.textViewRatingsTraktUser.setVisibility(View.VISIBLE);
            ratings.textViewRatingsTraktUser.setText(
                    TraktTools.buildUserRatingString(getActivity(), rating));
            ratingsContainer.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    rateMovie();
                }
            });
            CheatSheet.setup(ratingsContainer, R.string.action_rate);
        }
        ratingsContainer.setVisibility(View.VISIBLE);

        // genres
        binding.textViewMovieGenresLabel.setVisibility(View.VISIBLE);
        Utils.setValueOrPlaceholder(binding.textViewMovieGenres,
                TmdbTools.buildGenresString(tmdbMovie.genres));

        // trakt comments link
        binding.buttonMovieComments.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                Intent i = new Intent(getActivity(), TraktCommentsActivity.class);
                i.putExtras(TraktCommentsActivity.createInitBundleMovie(title, tmdbId));
                Utils.startActivityWithAnimation(getActivity(), i, v);
                Utils.trackAction(v.getContext(), TAG, "Comments");
            }
        });
        binding.buttonMovieComments.setVisibility(View.VISIBLE);

        // load poster, cache on external storage
        FrameLayout moviePosterFrame = binding.frameLayoutMoviePoster;
        if (TextUtils.isEmpty(tmdbMovie.poster_path)) {
            moviePosterFrame.setClickable(false);
            moviePosterFrame.setFocusable(false);
        } else {
            final String smallImageUrl = TmdbSettings.getImageBaseUrl(getActivity())
                    + TmdbSettings.POSTER_SIZE_SPEC_W342 + tmdbMovie.poster_path;
            final ImageView movieImageView = binding.imageViewMoviePoster;
            ServiceUtils.loadWithPicasso(getActivity(), smallImageUrl)
                    .into(movieImageView, new Callback.EmptyCallback() {
                        @Override
                        public void onSuccess() {
                            Bitmap bitmap
                                    = ((BitmapDrawable) movieImageView.getDrawable()).getBitmap();
                            Palette.from(bitmap).generate(new Palette.PaletteAsyncListener() {
                                @Override
                                public void onGenerated(Palette palette) {
                                    int color = palette.getVibrantColor(Color.WHITE);
                                    color = ColorUtils.setAlphaComponent(color, 50);
                                    binding.rootLayoutMovie.setBackgroundColor(color);
                                }
                            });
                        }
                    });
            // click listener for high resolution poster
            moviePosterFrame.setFocusable(true);
            moviePosterFrame.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    String largeImageUrl = TmdbSettings.getImageBaseUrl(getActivity())
                            + TmdbSettings.POSTER_SIZE_SPEC_ORIGINAL + tmdbMovie.poster_path;
                    Intent intent = new Intent(getActivity(), FullscreenImageActivity.class);
                    intent.putExtra(FullscreenImageActivity.EXTRA_PREVIEW_IMAGE, smallImageUrl);
                    intent.putExtra(FullscreenImageActivity.EXTRA_IMAGE, largeImageUrl);
                    Utils.startActivityWithAnimation(getActivity(), intent, view);
                }
            });
        }
    }

    private void populateMovieCreditsViews(final Credits credits) {
        if (credits == null) {
            setCastVisibility(false);
            setCrewVisibility(false);
            return;
        }

        // cast members
        if (credits.cast == null || credits.cast.size() == 0) {
            setCastVisibility(false);
        } else {
            setCastVisibility(true);
            PeopleListHelper.populateMovieCast(getActivity(), binding.moviePeople.containerCast,
                    credits, TAG);
        }

        // crew members
        if (credits.crew == null || credits.crew.size() == 0) {
            setCrewVisibility(false);
        } else {
            setCrewVisibility(true);
            PeopleListHelper.populateMovieCrew(getActivity(), binding.moviePeople.containerCrew,
                    credits, TAG);
        }
    }

    @Override
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(ExtensionManager.MovieActionReceivedEvent event) {
        if (event.movieTmdbId != tmdbId) {
            return;
        }
        loadMovieActionsDelayed();
    }

    @Subscribe
    public void onEvent(MovieTools.MovieChangedEvent event) {
        if (event.movieTmdbId != tmdbId) {
            return;
        }
        // re-query to update movie details
        restartMovieLoader();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventEpisodeTask(BaseNavDrawerActivity.ServiceActiveEvent event) {
        setMovieButtonsEnabled(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventEpisodeTask(BaseNavDrawerActivity.ServiceCompletedEvent event) {
        setMovieButtonsEnabled(true);
    }

    private void setMovieButtonsEnabled(boolean enabled) {
        binding.movieButtons.buttonMovieCheckIn.setEnabled(enabled);
        binding.movieButtons.buttonMovieWatched.setEnabled(enabled);
        binding.movieButtons.buttonMovieCollected.setEnabled(enabled);
        binding.movieButtons.buttonMovieWatchlisted.setEnabled(enabled);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void handleLanguageEvent(LanguageChoiceDialogFragment.LanguageChangedEvent event) {
        if (!AndroidUtils.isNetworkConnected(getContext())) {
            Toast.makeText(getContext(), R.string.offline, Toast.LENGTH_LONG).show();
            return;
        }

        String languageCode = getResources().getStringArray(
                R.array.languageCodesMovies)[event.selectedLanguageIndex];
        PreferenceManager.getDefaultSharedPreferences(getContext()).edit()
                .putString(DisplaySettings.KEY_LANGUAGE_MOVIES, languageCode)
                .apply();

        binding.progressBar.setVisibility(View.VISIBLE);

        // reload movie details and trailers (but not cast/crew info which is not language dependent)
        restartMovieLoader();
        Bundle args = new Bundle();
        args.putInt(InitBundle.TMDB_ID, tmdbId);
        getLoaderManager().restartLoader(MovieDetailsActivity.LOADER_ID_MOVIE_TRAILERS, args,
                mMovieTrailerLoaderCallbacks);
    }

    @Override
    public void loadMovieActions() {
        List<Action> actions = ExtensionManager.getInstance(getContext())
                .getLatestMovieActions(tmdbId);

        // no actions available yet, request extensions to publish them
        if (actions == null || actions.size() == 0) {
            actions = new ArrayList<>();

            if (movieDetails.tmdbMovie() != null) {
                com.battlelancer.seriesguide.api.Movie movie
                        = new com.battlelancer.seriesguide.api.Movie.Builder()
                        .tmdbId(tmdbId)
                        .imdbId(movieDetails.tmdbMovie().imdb_id)
                        .title(movieDetails.tmdbMovie().title)
                        .releaseDate(movieDetails.tmdbMovie().release_date)
                        .build();
                ExtensionManager.getInstance(getContext()).requestMovieActions(movie);
            }
        }

        Timber.d("loadMovieActions: received %s actions for %s", actions.size(), tmdbId);
        ActionsHelper.populateActions(getActivity().getLayoutInflater(),
                binding.containerMovieActions, actions, TAG);
    }

    Runnable movieActionsRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return; // we need an activity for this, abort.
            }
            loadMovieActions();
        }
    };

    @Override
    public void loadMovieActionsDelayed() {
        handler.removeCallbacks(movieActionsRunnable);
        handler.postDelayed(movieActionsRunnable, MovieActionsContract.ACTION_LOADER_DELAY_MILLIS);
    }

    private void rateMovie() {
        if (TraktCredentials.ensureCredentials(getActivity())) {
            RateDialogFragment newFragment = RateDialogFragment.newInstanceMovie(tmdbId);
            newFragment.show(getFragmentManager(), "ratedialog");
            Utils.trackAction(getActivity(), TAG, "Rate (trakt)");
        }
    }

    private void setCrewVisibility(boolean visible) {
        binding.moviePeople.labelCrew.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.moviePeople.containerCrew.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setCastVisibility(boolean visible) {
        binding.moviePeople.labelCast.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.moviePeople.containerCast.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void restartMovieLoader() {
        Bundle args = new Bundle();
        args.putInt(InitBundle.TMDB_ID, tmdbId);
        getLoaderManager().restartLoader(MovieDetailsActivity.LOADER_ID_MOVIE, args,
                mMovieLoaderCallbacks);
    }

    private LoaderManager.LoaderCallbacks<MovieDetails> mMovieLoaderCallbacks
            = new LoaderManager.LoaderCallbacks<MovieDetails>() {
        @Override
        public Loader<MovieDetails> onCreateLoader(int loaderId, Bundle args) {
            return new MovieLoader(SgApp.from(getActivity()), args.getInt(InitBundle.TMDB_ID));
        }

        @Override
        public void onLoadFinished(Loader<MovieDetails> movieLoader, MovieDetails movieDetails) {
            if (!isAdded()) {
                return;
            }
            MovieDetailsFragment.this.movieDetails = movieDetails;
            binding.progressBar.setVisibility(View.GONE);

            // we need at least values from database or tmdb
            if (movieDetails.tmdbMovie() != null) {
                populateMovieViews();
                loadMovieActions();
                getActivity().invalidateOptionsMenu();
            } else {
                // if there is no local data and loading from network failed
                String emptyText;
                if (AndroidUtils.isNetworkConnected(getContext())) {
                    emptyText = getString(R.string.api_error_generic, getString(R.string.tmdb));
                } else {
                    emptyText = getString(R.string.offline);
                }
                binding.textViewMovieDescription.setText(emptyText);
            }
        }

        @Override
        public void onLoaderReset(Loader<MovieDetails> movieLoader) {
            // nothing to do
        }
    };

    private LoaderManager.LoaderCallbacks<Videos.Video> mMovieTrailerLoaderCallbacks
            = new LoaderManager.LoaderCallbacks<Videos.Video>() {
        @Override
        public Loader<Videos.Video> onCreateLoader(int loaderId, Bundle args) {
            return new MovieTrailersLoader(SgApp.from(getActivity()),
                    args.getInt(InitBundle.TMDB_ID));
        }

        @Override
        public void onLoadFinished(Loader<Videos.Video> trailersLoader, Videos.Video trailer) {
            if (!isAdded()) {
                return;
            }
            if (trailer != null) {
                MovieDetailsFragment.this.trailer = trailer;
                getActivity().invalidateOptionsMenu();
            }
        }

        @Override
        public void onLoaderReset(Loader<Videos.Video> trailersLoader) {
            // do nothing
        }
    };

    private LoaderManager.LoaderCallbacks<Credits> mMovieCreditsLoaderCallbacks
            = new LoaderManager.LoaderCallbacks<Credits>() {
        @Override
        public Loader<Credits> onCreateLoader(int loaderId, Bundle args) {
            return new MovieCreditsLoader(SgApp.from(getActivity()),
                    args.getInt(InitBundle.TMDB_ID));
        }

        @Override
        public void onLoadFinished(Loader<Credits> creditsLoader, Credits credits) {
            if (!isAdded()) {
                return;
            }
            populateMovieCreditsViews(credits);
        }

        @Override
        public void onLoaderReset(Loader<Credits> creditsLoader) {
            // do nothing
        }
    };

    private class ToolbarScrollChangeListener implements NestedScrollView.OnScrollChangeListener {
        private final int overlayThresholdPx;
        private final int titleThresholdPx;

        private SparseArrayCompat<Boolean> showOverlayMap;
        private boolean showOverlay;
        private boolean showTitle;

        public ToolbarScrollChangeListener(int overlayThresholdPx, int titleThresholdPx) {
            this.overlayThresholdPx = overlayThresholdPx;
            this.titleThresholdPx = titleThresholdPx;
            // we have determined by science that a capacity of 2 is good in our case :)
            showOverlayMap = new SparseArrayCompat<>(2);
        }

        @Override
        public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX,
                int oldScrollY) {
            ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
            if (actionBar == null) {
                return;
            }

            int viewId = v.getId();

            boolean shouldShowOverlay = scrollY > overlayThresholdPx;
            showOverlayMap.put(viewId, shouldShowOverlay);
            for (int i = 0; i < showOverlayMap.size(); i++) {
                shouldShowOverlay |= showOverlayMap.valueAt(i);
            }

            if (!showOverlay && shouldShowOverlay) {
                int primaryColor = ContextCompat.getColor(v.getContext(),
                        Utils.resolveAttributeToResourceId(v.getContext().getTheme(),
                                R.attr.sgColorBackgroundDim));
                actionBar.setBackgroundDrawable(new ColorDrawable(primaryColor));
            } else if (showOverlay && !shouldShowOverlay) {
                actionBar.setBackgroundDrawable(null);
            }
            showOverlay = shouldShowOverlay;

            // only main container should show/hide title
            if (viewId == R.id.contentContainerMovie) {
                boolean shouldShowTitle = scrollY > titleThresholdPx;
                if (!showTitle && shouldShowTitle) {
                    if (movieDetails != null && movieDetails.tmdbMovie() != null) {
                        actionBar.setTitle(movieDetails.tmdbMovie().title);
                        actionBar.setDisplayShowTitleEnabled(true);
                    }
                } else if (showTitle && !shouldShowTitle) {
                    actionBar.setDisplayShowTitleEnabled(false);
                }
                showTitle = shouldShowTitle;
            }
        }
    }
}
