/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.util;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.AsyncTask;

import java.util.ArrayList;

import se.lublin.humla.HumlaService;
import se.lublin.humla.model.Server;
import se.lublin.humla.Constants;

/**
 * Constructs an intent for connection to a MumlaService and executes it.
 * Created by andrew on 20/08/14.
 */
public class ServerConnectTask extends AsyncTask<Server, Void, Intent> {

    private static final String TAG = ServerConnectTask.class.getName();
    private Context mContext;

    public ServerConnectTask(Context context) {
        mContext = context;
    }

    @Override
    protected Intent doInBackground(Server... params) {
        Server server = params[0];

        Intent connectIntent = new Intent(mContext, HumlaService.class);
        connectIntent.putExtra(HumlaService.EXTRAS_SERVER, server);
        connectIntent.putExtra(HumlaService.EXTRAS_CLIENT_NAME, "Mumble SSVR");

        connectIntent.putExtra(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_CONTINUOUS);
        connectIntent.putExtra(HumlaService.EXTRAS_DETECTION_THRESHOLD, (float)0.5);
        connectIntent.putExtra(HumlaService.EXTRAS_AMPLITUDE_BOOST, (float)1.0);
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT, true);
        connectIntent.putExtra(HumlaService.EXTRAS_AUTO_RECONNECT_DELAY, 10000);
        connectIntent.putExtra(HumlaService.EXTRAS_USE_OPUS, true);
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_RATE, 48000);
        connectIntent.putExtra(HumlaService.EXTRAS_INPUT_QUALITY, 40000);
        connectIntent.putExtra(HumlaService.EXTRAS_FORCE_TCP, false);
        connectIntent.putExtra(HumlaService.EXTRAS_USE_TOR, false);
        connectIntent.putStringArrayListExtra(HumlaService.EXTRAS_ACCESS_TOKENS, new ArrayList<String>());
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
        connectIntent.putExtra(HumlaService.EXTRAS_AUDIO_STREAM, AudioManager.STREAM_MUSIC);
        connectIntent.putExtra(HumlaService.EXTRAS_FRAMES_PER_PACKET, 2);
        connectIntent.putExtra(HumlaService.EXTRAS_HALF_DUPLEX, false);
        connectIntent.putExtra(HumlaService.EXTRAS_ENABLE_PREPROCESSOR, true);

        connectIntent.setAction(HumlaService.ACTION_CONNECT);
        return connectIntent;
    }

    @Override
    protected void onPostExecute(Intent intent) {
        super.onPostExecute(intent);
        mContext.startService(intent);
    }
}