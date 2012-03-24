/*
 * Copyright 2011-2012 the original author or authors.
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

package piuk.blockchain.android;

import java.math.BigInteger;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import piuk.MyBlockChain;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.widget.Toast;

import com.google.bitcoin.core.AbstractPeerEventListener;
import com.google.bitcoin.core.AbstractWalletEventListener;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.Peer;
import com.google.bitcoin.core.PeerEventListener;
import com.google.bitcoin.core.ScriptException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionInput;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.core.WalletEventListener;
import piuk.blockchain.R;
import piuk.blockchain.android.ui.WalletActivity;
import piuk.blockchain.android.util.WalletUtils;

/**
 * @author Andreas Schildbach
 */
public class BlockchainService extends android.app.Service
{
	public static final String ACTION_PEER_STATE = BlockchainService.class.getName() + ".peer_state";
	public static final String ACTION_PEER_STATE_NUM_PEERS = "num_peers";

	public static final String ACTION_BLOCKCHAIN_STATE = BlockchainService.class.getName() + ".blockchain_state";
	public static final String ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_DATE = "best_chain_date";
	public static final String ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_HEIGHT = "best_chain_height";
	public static final String ACTION_BLOCKCHAIN_STATE_DOWNLOAD = "download";
	public static final int ACTION_BLOCKCHAIN_STATE_DOWNLOAD_OK = 0;
	public static final int ACTION_BLOCKCHAIN_STATE_DOWNLOAD_STORAGE_PROBLEM = 1;
	public static final int ACTION_BLOCKCHAIN_STATE_DOWNLOAD_POWER_PROBLEM = 2;
	public static final int ACTION_BLOCKCHAIN_STATE_DOWNLOAD_NETWORK_PROBLEM = 4;

	private WalletApplication application;

	private MyBlockChain blockChain;

	private final Handler handler = new Handler();
	private final Handler delayHandler = new Handler();

	private NotificationManager nm;
	private static final int NOTIFICATION_ID_CONNECTED = 0;
	private static final int NOTIFICATION_ID_COINS_RECEIVED = 1;

	private int notificationCount = 0;
	private BigInteger notificationAccumulatedAmount = BigInteger.ZERO;
	private final List<Address> notificationAddresses = new LinkedList<Address>();

	private final WalletEventListener walletEventListener = new AbstractWalletEventListener()
	{
		@Override
		public void onCoinsReceived(final Wallet wallet, final Transaction tx, final BigInteger prevBalance, final BigInteger newBalance)
		{
			try
			{
				
				System.out.println("onCoinsReceived()");
				
				final TransactionInput input = tx.getInputs().get(0);
				final Address from = input.getFromAddress();
				final BigInteger amount = tx.getValue(wallet);

				handler.post(new Runnable()
				{
					public void run()
					{
						notifyCoinsReceived(from, amount);

						notifyWidgets();
						
					}
				});
			}
			catch (final ScriptException x)
			{
				throw new RuntimeException(x);
			}
		}
	};

	private void notifyCoinsReceived(final Address from, final BigInteger amount)
	{
		System.out.println("Notify ");

		if (notificationCount == 1)
			nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);

		notificationCount++;
		notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
		if (from != null && !notificationAddresses.contains(from))
			notificationAddresses.add(from);

		final String tickerMsg = getString(R.string.notification_coins_received_msg, WalletUtils.formatValue(amount))
				+ (Constants.TEST ? " [testnet]" : "");

		final String msg = getString(R.string.notification_coins_received_msg, WalletUtils.formatValue(notificationAccumulatedAmount))
				+ (Constants.TEST ? " [testnet]" : "");

		final StringBuilder text = new StringBuilder();
		for (final Address address : notificationAddresses)
		{
			if (text.length() > 0)
				text.append(", ");
			text.append(address.toString());
		}

		if (text.length() == 0)
			text.append("unknown");

		text.insert(0, "From ");

		final Notification notification = new Notification(R.drawable.stat_notify_received, tickerMsg, System.currentTimeMillis());
		notification.setLatestEventInfo(BlockchainService.this, msg, text,
				PendingIntent.getActivity(BlockchainService.this, 0, new Intent(BlockchainService.this, WalletActivity.class), 0));

		notification.number = notificationCount == 1 ? 0 : notificationCount;
		notification.sound = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alert);

		nm.notify(NOTIFICATION_ID_COINS_RECEIVED, notification);

		
		Toast.makeText(application, tickerMsg, Toast.LENGTH_LONG).show();
	}

	public void cancelCoinsReceived()
	{
		notificationCount = 0;
		notificationAccumulatedAmount = BigInteger.ZERO;
		notificationAddresses.clear();

		nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
	}

	private final PeerEventListener peerEventListener = new AbstractPeerEventListener()
	{
		private final AtomicLong lastMessageTime = new AtomicLong(0);

		@Override
		public void onBlocksDownloaded(final Peer peer, final Block block, final int blocksLeft)
		{
			delayHandler.removeCallbacksAndMessages(null);

			final long now = System.currentTimeMillis();

			if (now - lastMessageTime.get() > Constants.BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS)
				delayHandler.post(runnable);
			else
				delayHandler.postDelayed(runnable, Constants.BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);
		}

		private final Runnable runnable = new Runnable()
		{
			public void run()
			{
				lastMessageTime.set(System.currentTimeMillis());

				final Date bestChainDate = new Date(blockChain.getChainHead().getHeader().getTimeSeconds() * 1000);
				final int bestChainHeight = blockChain.getBestChainHeight();

				sendBroadcastBlockchainState(bestChainDate, bestChainHeight, ACTION_BLOCKCHAIN_STATE_DOWNLOAD_OK);
			}
		};
		
		@Override
		public void onPeerConnected(final Peer peer, final int peerCount)
		{
			changed(peerCount);
		}

		@Override
		public void onPeerDisconnected(final Peer peer, final int peerCount)
		{
			changed(peerCount);
		}

		private void changed(final int numPeers)
		{
			handler.post(new Runnable()
			{
				public void run()
				{
					if (numPeers == 0)
					{
						nm.cancel(NOTIFICATION_ID_CONNECTED);
					}
					else
					{
						final String msg = getString(R.string.notification_peers_connected_msg, numPeers);
						System.out.println("Peer connected, " + msg);

						final Notification notification = new Notification(R.drawable.stat_sys_peers, null, 0);
						notification.flags |= Notification.FLAG_ONGOING_EVENT;
						notification.iconLevel = numPeers > 4 ? 4 : numPeers;
						notification.setLatestEventInfo(BlockchainService.this, getString(R.string.app_name) + (Constants.TEST ? " [testnet]" : ""),
								msg,
								PendingIntent.getActivity(BlockchainService.this, 0, new Intent(BlockchainService.this, WalletActivity.class), 0));
						nm.notify(NOTIFICATION_ID_CONNECTED, notification);
					}

					// send broadcast
					sendBroadcastPeerState(numPeers);
				}
			});
		}
	};

	private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver()
	{
		private boolean hasConnectivity;
		private boolean hasPower;
		private boolean hasStorage = true;

		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			final String action = intent.getAction();

			if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action))
			{
				hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
				final String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
				// final boolean isFailover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
				System.out.println("network is " + (hasConnectivity ? "up" : "down") + (reason != null ? ": " + reason : ""));

				check();
			}
			else if (Intent.ACTION_BATTERY_CHANGED.equals(action))
			{
				final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
				final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
				final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
				hasPower = plugged != 0 || level > scale / 10;
				System.out.println("battery changed: level=" + level + "/" + scale + " plugged=" + plugged);

				check();
			}
			else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action))
			{
				hasStorage = false;
				System.out.println("device storage low");

				check();
			}
			else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action))
			{
				hasStorage = true;
				System.out.println("device storage ok");

				check();
			}
		}

		private void check()
		{
			final boolean hasEverything = hasConnectivity && hasPower && hasStorage;

			if (hasEverything) {
				blockChain.addPeerEventListener(peerEventListener);

				System.out.println("Connect");
				
				
				if (!blockChain.getRemoteWallet().isUptoDate(Constants.MultiAddrTimeThreshold)) {
					
					System.out.println("Sync Wallet");
					
					application.syncWithMyWallet();
				}
			
				if (!blockChain.isConnected())
					blockChain.start();
			}
			
			if (blockChain.getChainHead() == null)
				return;

			final Date bestChainDate = new Date(blockChain.getChainHead().getHeader().getTimeSeconds() * 1000);
			final int bestChainHeight = blockChain.getBestChainHeight();
			final int download = (hasConnectivity ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_NETWORK_PROBLEM)
					| (hasPower ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_POWER_PROBLEM)
					| (hasStorage ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_STORAGE_PROBLEM);

			sendBroadcastBlockchainState(bestChainDate, bestChainHeight, download);
		}
	};

	public class LocalBinder extends Binder
	{
		public BlockchainService getService()
		{
			return BlockchainService.this;
		}
	}

	private final IBinder mBinder = new LocalBinder();

	@Override
	public IBinder onBind(final Intent intent)
	{
		return mBinder;
	}

	@Override
	public void onCreate()
	{
		System.out.println("service onCreate()");

		super.onCreate();

		nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		
		application = (WalletApplication) getApplication();
		
		application.getWallet().addEventListener(walletEventListener);

		sendBroadcastPeerState(0);

		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
		intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
		intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
		registerReceiver(broadcastReceiver, intentFilter);
	
		try {
			blockChain = new MyBlockChain(Constants.NETWORK_PARAMETERS, application.getRemoteWallet());
		} catch (Exception e) {
			e.printStackTrace();
		}	
	}
	
	@Override
	public void onDestroy()
	{
		System.out.println("service onDestroy()");

		application.getWallet().removeEventListener(walletEventListener);

		blockChain.stop();

		unregisterReceiver(broadcastReceiver);

		removeBroadcastPeerState();
		
		removeBroadcastBlockchainState();

		delayHandler.removeCallbacksAndMessages(null);

		handler.postDelayed(new Runnable()
		{
			public void run()
			{
				nm.cancel(NOTIFICATION_ID_CONNECTED);
			}
		}, Constants.SHUTDOWN_REMOVE_NOTIFICATION_DELAY);

		super.onDestroy();
	}

	private void sendBroadcastPeerState(final int numPeers)
	{
		final Intent broadcast = new Intent(ACTION_PEER_STATE);
		broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers);
		sendStickyBroadcast(broadcast);
	}

	private void removeBroadcastPeerState()
	{
		removeStickyBroadcast(new Intent(ACTION_PEER_STATE));
	}

	private void sendBroadcastBlockchainState(final Date chainheadDate, final int chainheadHeight, final int download)
	{
		final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_DATE, chainheadDate);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_HEIGHT, chainheadHeight);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_DOWNLOAD, download);

		sendStickyBroadcast(broadcast);
	}

	private void removeBroadcastBlockchainState()
	{
		removeStickyBroadcast(new Intent(ACTION_BLOCKCHAIN_STATE));
	}

	public void notifyWidgets()
	{
		final Context context = getApplicationContext();

		// notify widgets
		final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
		for (final AppWidgetProviderInfo providerInfo : appWidgetManager.getInstalledProviders())
		{
			// limit to own widgets
			if (providerInfo.provider.getPackageName().equals(context.getPackageName()))
			{
				final Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetManager.getAppWidgetIds(providerInfo.provider));
				context.sendBroadcast(intent);
			}
		}
	}
}