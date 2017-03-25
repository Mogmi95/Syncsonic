package fr.mogmi.syncsonic;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import java.io.File;
import java.util.ArrayList;

import fr.mogmi.syncsonic.model.Ping;
import fr.mogmi.syncsonic.model.SubsonicResponse;
import fr.mogmi.syncsonic.model.directory.Child;
import fr.mogmi.syncsonic.model.directory.DirectoryContainer;
import fr.mogmi.syncsonic.model.starred.StarredAlbum;
import fr.mogmi.syncsonic.model.starred.StarredArtist;
import fr.mogmi.syncsonic.model.starred.StarredContainer;
import fr.mogmi.syncsonic.model.starred.StarredSong;
import fr.mogmi.syncsonic.network.SubsonicHelper;
import fr.mogmi.syncsonic.settings.SettingsActivity;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static fr.mogmi.syncsonic.R.id.fab;

public class MainActivity extends AppCompatActivity {

    private final static int RC_PERMISSION_WRITE_EXTERNAL_STORAGE = 42;

    private RecyclerView recyclerView;
    private SubsonicHelper subsonicHelper;
    private DownloadManager mgr;
    private FloatingActionButton actionButton;

    private ArrayList<SyncedItem> syncedItems = new ArrayList<>();
    private DownloadsAdapter downloadsAdapter;

    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        downloadsAdapter = new DownloadsAdapter(syncedItems);
        recyclerView.setAdapter(downloadsAdapter);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mgr = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        actionButton = (FloatingActionButton) findViewById(fab);
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sync();
            }
        });

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(prefs.getString("server_url", ""))
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        subsonicHelper = new SubsonicHelper(
                retrofit,
                SubsonicHelper.DEFAULT_FORMAT,
                SubsonicHelper.DEFAULT_APP_NAME,
                SubsonicHelper.DEFAULT_APP_VERSION,
                prefs.getString("server_login", ""),
                prefs.getString("server_password", "")
        );

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_PERMISSION_WRITE_EXTERNAL_STORAGE);

        ping();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == RC_PERMISSION_WRITE_EXTERNAL_STORAGE) {
            // TODO Handle permission
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent openSettings = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(openSettings);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void sync() {
        recyclerView.setVisibility(View.VISIBLE);

        Animation rotation = AnimationUtils.loadAnimation(this, R.anim.spin);
        rotation.setRepeatCount(Animation.INFINITE);
        actionButton.startAnimation(rotation);

        syncedItems.clear();
        downloadsAdapter.notifyDataSetChanged();
        Call<SubsonicResponse<StarredContainer>> starred = subsonicHelper.getStarred();
        Log.i("STARRED", String.valueOf(starred.request().url()));
        starred.enqueue(new Callback<SubsonicResponse<StarredContainer>>() {
            @Override
            public void onResponse(Call<SubsonicResponse<StarredContainer>> call, Response<SubsonicResponse<StarredContainer>> response) {
                for (StarredArtist artist : response.body().subsonicResponse.starred.artists) {
                    downloadDirectory(artist.id);
                }
                for (StarredAlbum album : response.body().subsonicResponse.starred.albums) {
                    downloadDirectory(album.id);
                }
                for (StarredSong song : response.body().subsonicResponse.starred.songs) {
                    startDownload(song.path, song.id, song.artist, song.title);
                }
                actionButton.clearAnimation();
            }

            @Override
            public void onFailure(Call<SubsonicResponse<StarredContainer>> call, Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void downloadDirectory(@NonNull String id) {
        Call<SubsonicResponse<DirectoryContainer>> directory = subsonicHelper.getDirectory(id);
        directory.enqueue(new Callback<SubsonicResponse<DirectoryContainer>>() {
            @Override
            public void onResponse(Call<SubsonicResponse<DirectoryContainer>> call, Response<SubsonicResponse<DirectoryContainer>> response) {
                for (Child child : response.body().subsonicResponse.directory.childs) {
                    if (child.isDirectory) {
                        downloadDirectory(child.id);
                    } else {
                        startDownload(child.path, child.id, child.artist, child.title);
                    }
                }
            }

            @Override
            public void onFailure(Call<SubsonicResponse<DirectoryContainer>> call, Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void ping() {
        final Call<SubsonicResponse<Ping>> ping = subsonicHelper.getPing();

        Log.i("PING", String.valueOf(ping.request().url()));

        ping.enqueue(new Callback<SubsonicResponse<Ping>>() {
            @Override
            public void onResponse(Call<SubsonicResponse<Ping>> call, Response<SubsonicResponse<Ping>> response) {
                Log.i("PING", response.body().subsonicResponse.status);
            }

            @Override
            public void onFailure(Call<SubsonicResponse<Ping>> call, Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void startDownload(@NonNull String path,
                               @NonNull String id,
                               @NonNull String artist,
                               @NonNull String title) {
        String dirPath = path.substring(0, path.lastIndexOf("/"));
        String filename = path.substring(path.lastIndexOf("/"), path.length());

        // Creating the directory
        File musicDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        String directoryPath = "Syncsonic/" + dirPath;
        File directory = new File(musicDirectory, directoryPath);
        directory.mkdirs();
        // Creating the file
        File file = new File(directory, filename);
        if (!file.exists()) {
            String url = subsonicHelper.getDownloadLink(id);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setDestinationUri(Uri.fromFile(file));
            request.setTitle(title);
            mgr.enqueue(request);
            syncedItems.add(new SyncedItem(true, artist, title));
        } else {
            syncedItems.add(new SyncedItem(false, artist, title));
        }
        downloadsAdapter.notifyDataSetChanged();
    }

}
