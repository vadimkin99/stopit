package com.vk.appStopit;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class stopit extends Activity implements View.OnClickListener {
    /** Called when the activity is first created. */
	
    // Controls
    //
	private EditText m_etSendTo = null;
	private Button m_bPick = null;
	private Button m_bStart = null;
	private Button m_bStop = null;
	private TextView m_tvFrequency = null;
	private SeekBar m_sbFrequency = null;
	private TextView m_tvMessages = null;
	
    // Private data
    //
	private String m_phone = null;
	private String m_sendTo = null;
	private String[] m_stockMessages = null;
	private final int m_defaultFrequency = 60;
	private int m_frequency;
	private int m_lastFrequency;
	private int m_frequencyOffset = 10;
	private int m_waitBeforeFirstPester = 0;
	private volatile Thread m_pesteringThread = null;
	private volatile boolean m_pesteringThreadActive = false;
	private long m_firstPester = 0;
	private int m_pesterTimeLimitSecDefault = 10 * 60; // 10 min
	private int m_pesterTimeLimitSec;
	private long m_lastPester = 0;
	private final int MENU_ID_EXIT = Menu.FIRST;
	private final int MENU_ID_PREFERENCES = Menu.FIRST + 1;
	
	private static final int PICK_CONTACT_REQUEST = 1;
	private static final int EDIT_TIME_LIMIT_REQUEST = 2;
	
	private Random random = new Random();

	private boolean m_sendToManuallyEdited = false;
	
	private boolean m_messageDelivered = false;
	private boolean m_messageSent = false;
	private String m_messageNotSentReason = new String();
	
    private final String SENT = "SMS_SENT";
    private final String DELIVERED = "SMS_DELIVERED";
	
	// Messages to this handler are sent from the background timer thread
	// The handler runs on the UI thread and manipulates the UI
	Handler outputMessageToScreenHandler = new Handler() {
		@Override
		public void handleMessage( Message msg) {

			// send the text message
			//
			Bundle data = msg.getData();
			String newPest = data.getString("pest");
			addMessage("Sending \"" + newPest + "\"");
			
			// see if it's time to tell the user that perhaps we should stop
			//
			if((System.currentTimeMillis() - m_firstPester) / 1000 > m_pesterTimeLimitSec) {
				showLimitDialog();
			}
		}
	};
	
	Handler warnUserAboutSMSProblemHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			checkMessageStatusAndWarnUser();
		}
	};
	
	// Ask the user if it's time to stop
	// Pestering is stopped while the dialog is up
	// In response to the user's action, pestering is resumed or stopped for good
	//
	void showLimitDialog() {
		
		stopPestering(false);
		
		new AlertDialog.Builder(this)
		.setTitle("Stop It!")
		.setMessage("You've been pestering this person for a while, keep on doing it?")
		.setPositiveButton("Yes", 
			new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					startPestering(false);
				}
			})
		.setNegativeButton("No", 
			new DialogInterface.OnClickListener() {
				
				public void onClick(DialogInterface dialog, int which) {
					stopPestering(true);
				}
			})
		.show();
	}
	
	// Add message to the status window, and scroll the window to the bottom
	// If the window is "full" (reached the limit of messages), delete the
	// first message
	//
	private void addMessage(String msg) {
		String contents = m_tvMessages.getText().toString();
		if( countMatches(contents, '\n') > 40) {
			contents = contents.substring(contents.indexOf('\n') + 1);
		}
    	m_tvMessages.setText(
    			contents + "\n" + getCurrentTime() + " " + msg);
    	final ScrollView sv = (ScrollView) findViewById(R.id.msgScrollView);
    	sv.post( new Runnable() {
    		public void run() {
    	    	sv.fullScroll(ScrollView.FOCUS_DOWN);
    		}
    	});
	}
	
	// Count character matches in a string
	//
	private static int countMatches(final String s, final char c) {
		int ret = 0;
		
		int lastOccurrence = s.indexOf(c);
		while( lastOccurrence != -1) {
			ret++;
			lastOccurrence = s.indexOf(c, lastOccurrence + 1);
		}
		
		return ret;
	}
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	
    	if( SmsManager.getDefault() == null) {
			new AlertDialog.Builder(this)
			.setTitle("Stop It!")
			.setMessage("We are sorry, it appears that your device does not support SMS messaging.")
			.setNeutralButton("Close", new DialogInterface.OnClickListener() {
				
				public void onClick(DialogInterface dialog, int which) {
					finish();
				}
			})
			.show();
    	}
    	
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Control var initialization
        //
        m_etSendTo = (EditText) findViewById(R.id.sendTo);
        m_etSendTo.setHint("To");
        m_bPick = (Button) findViewById(R.id.pickTo);
        m_bPick.setOnClickListener(this);
        m_bStart = (Button) findViewById(R.id.start);
        m_bStart.setOnClickListener(this);
        m_bStop = (Button) findViewById(R.id.stop);
        m_bStop.setOnClickListener(this);
        m_tvFrequency = (TextView) findViewById(R.id.frequency);
        m_sbFrequency = (SeekBar) findViewById(R.id.frequencySeek);
        m_tvMessages = (TextView) findViewById(R.id.messages);
        
        m_etSendTo.setOnKeyListener(new View.OnKeyListener() {
        	public boolean onKey(View v, int keyCode, KeyEvent e) {
        		m_sendToManuallyEdited = true; return false;
        	}
        });
        
        m_sbFrequency.setMax(300 - m_frequencyOffset);
        m_sbFrequency.setOnSeekBarChangeListener(frequencyChangeListener);
        
        // Private data initialization
        //
        m_stockMessages = new String[] {
        		"hang up!",
        		"I'm sitting accross from you!",
        		"Oh stop fiddling with your phone already!"
        };
        
        initSmsCallbacks();
    }
    
    @Override
    public void onStart() {
    	recallPreferences();
    	super.onStart();
    }
    
    private void recallPreferences() {
    	
    	SharedPreferences pref = getPreferences(MODE_PRIVATE);
    	m_phone = pref.getString("phone", null);
    	m_sendTo = pref.getString("sendTo", null);
    	if( m_sendTo != null) {
    		m_etSendTo.setText(m_sendTo);
    	} else if( m_phone != null) {
    		m_etSendTo.setText(m_phone);
    	}
    	setFrequency( pref.getInt("frequency", m_defaultFrequency));
    	m_sbFrequency.setProgress(m_frequency - m_frequencyOffset);
    	
  		m_pesterTimeLimitSec = getTimeLimitPref();
    }
    
    private int getTimeLimitPref() {
    	SharedPreferences configPref = PreferenceManager.getDefaultSharedPreferences(this);
    	String def = (new Integer(m_pesterTimeLimitSecDefault)).toString();
    	String val = configPref.getString("timeLimit", def);
    	return Integer.parseInt(val);
    }
    
    @Override
    public void onStop() {
    	super.onStop();
    }
    
    private void savePreferences() {
		SharedPreferences pref = getPreferences(MODE_PRIVATE);
		SharedPreferences.Editor editor = pref.edit();
		editor.putInt("frequency", m_frequency);
		if(m_sendToManuallyEdited) {
			editor.putString("phone", m_phone);
			editor.remove("sendTo");
		} else {
			editor.putString("phone", m_phone);
			editor.putString("sendTo", m_sendTo);
		}
		editor.commit();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	menu.add(Menu.NONE, MENU_ID_PREFERENCES, Menu.NONE, "Preferences");
    	menu.add(Menu.NONE, MENU_ID_EXIT, Menu.NONE, "Exit");
    	return( super.onCreateOptionsMenu(menu));
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	switch (item.getItemId()) {
    	case MENU_ID_PREFERENCES:
    		startActivityForResult(new Intent(this, EditPreferences.class), EDIT_TIME_LIMIT_REQUEST);
    		return true;
    	case MENU_ID_EXIT:
    		finish();
    		return true;
    	}
    	return false;
    }
    
    @Override
	public void onClick(View view) {
    	if( view == m_bStart) {
    		processStartButton();
    	} else if( view == m_bStop) {
    		stopPestering(true);
    	} else if( view == m_bPick) {
    		pickContact();
    	}
    }

    private void processStartButton() {
    	
		final String sendToFromUI = m_etSendTo.getText().toString();
		
		// empty phone number check
		//
		if(sendToFromUI.matches("\\s*")) {
			new AlertDialog.Builder(this)
			.setTitle("Stop It!")
			.setMessage("Must pick a contact or enter a phone number")
			.setNeutralButton("Ok", new DialogInterface.OnClickListener() {
				
				public void onClick(DialogInterface dialog, int which) {}
			})
			.show();
			return;
		}
		
		// if user typed in phone # that was not picked from contacts
		// verify it, or ask the user if he really meant it
		//
		if( m_sendTo == null || !m_sendTo.equals(sendToFromUI)) {

			if(sendToFromUI.matches("^(1?(-?\\d{3})-?)?(\\d{3})(-?\\d{4})$")) {
				m_phone = sendToFromUI;
				startPestering(true);
			} else {
				DialogInterface.OnClickListener yesListener = new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						m_phone = sendToFromUI;
						startPestering(true);
					}
				};
				DialogInterface.OnClickListener noListener = new DialogInterface.OnClickListener() {
					
					public void onClick(DialogInterface dialog, int which) {
						m_phone = null;
					}
				};
				new AlertDialog.Builder(this)
					.setTitle("Stop It!")
					.setMessage("This does not seem to be a valid phone number, try anyway?")
					.setPositiveButton("Yes", yesListener)
					.setNegativeButton("No", noListener)
					.show();
			}
		}
    	
		View v = this.getCurrentFocus();
		if( v != null) {
			InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			inputManager.hideSoftInputFromWindow(v.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
		}

		savePreferences();
    }
    
    private void pickContact() {
    	Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
    	startActivityForResult(intent, PICK_CONTACT_REQUEST);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	super.onActivityResult(requestCode, resultCode, data);

    	if( requestCode == PICK_CONTACT_REQUEST) {
    		processPickContact(data);
    	} else if (requestCode == EDIT_TIME_LIMIT_REQUEST) {
      		m_pesterTimeLimitSec = getTimeLimitPref();
    	}
    }
    
    private void processPickContact(Intent data) {
    	
    	if( data == null) {
    		return;  // user clicked back while list of contacts was displayed
    	}
    	
    	m_sendToManuallyEdited = false;
    	
    	Uri contactData = data.getData();
    	Cursor cursor = managedQuery(contactData, null, null, null, null);
    	ContentResolver resolver = getContentResolver(); 
    	
    	if(cursor.moveToFirst()) {
    		String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID));
    		String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
    		
    		Cursor phoneCursor = resolver.query(
    				ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
    				null,  // projection
    				ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",  // selection
    				new String[] {id},  // selection args
    				null);  // sort

    		boolean havePhone = phoneCursor.moveToFirst(); 
    		while(havePhone) {
    			if( phoneCursor.getInt(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE))
    					== ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
    				m_phone = phoneCursor.getString(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
        			m_sendTo = name;
        			savePreferences();
        			recallPreferences();
        			break;
    			}
    			havePhone = phoneCursor.moveToNext();
    		}
    		
    		if( !havePhone) {
    			new AlertDialog.Builder(this)
    				.setTitle("Stop It!")
    				.setMessage("This contact does not have a mobile phone")
    				.setNeutralButton("Close", 
    						new DialogInterface.OnClickListener() {
    							public void onClick(DialogInterface dlg, int i) {}})
    				.show();
    		}
    	}
    }
    
    private String getCurrentTime() {
    	DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    	Date date = new Date();
    	return dateFormat.format(date);
    }
    
    public void startPestering(boolean addMessage) {
    	if( addMessage) {
    		addMessage("Starting...");
    	}
    	m_firstPester = System.currentTimeMillis();
    	m_pesteringThreadActive = true;
    	m_pesteringThread = new Thread( new Runnable() {
    		public void run() {
    			if(m_waitBeforeFirstPester > 0) {
    				m_waitBeforeFirstPester = 0;
        			try {
        				Thread.sleep(m_waitBeforeFirstPester * 1000);
        			} catch (InterruptedException e) {
        				Thread.currentThread().interrupt();
        			}
    			}
    			while(m_pesteringThreadActive) {
    				
    				if( m_pesteringThreadActive) {
	        			pester();
	        			try {
	        				Thread.sleep(m_frequency * 1000);
	        			} catch (InterruptedException e) {
	        				Thread.currentThread().interrupt();
	        				break;
	        			}
    				}
    			}
    		}
    	});
    	m_pesteringThread.setDaemon(true);
    	m_pesteringThread.start();
    }
    
    private void checkMessageStatusAndWarnUser() {
    	
		stopPestering(false);
		
    	if( !m_messageSent) {
    		new AlertDialog.Builder(this)
    		.setTitle("Stop It!")
    		.setMessage("Having trouble sending messages, reason: " + m_messageNotSentReason + ". Keep trying?")
    		.setPositiveButton("Yes", 
    			new DialogInterface.OnClickListener() {
    				public void onClick(DialogInterface dialog, int which) {
    					startPestering(false);
    				}
    			})
    		.setNegativeButton("No", 
    			new DialogInterface.OnClickListener() {
    				
    				public void onClick(DialogInterface dialog, int which) {
    					stopPestering(true);
    				}
    			})
    		.show();
    	}
    	
    	else if( !m_messageDelivered) {
    		new AlertDialog.Builder(this)
    		.setTitle("Stop It!")
    		.setMessage("The message has been sent successfully, but its delivery could not be verified. Keep sending messages?")
    		.setPositiveButton("Yes", 
    			new DialogInterface.OnClickListener() {
    				public void onClick(DialogInterface dialog, int which) {
    					startPestering(false);
    				}
    			})
    		.setNegativeButton("No", 
    			new DialogInterface.OnClickListener() {
    				
    				public void onClick(DialogInterface dialog, int which) {
    					stopPestering(true);
    				}
    			})
    		.show();
    	}
    }
    
    public void stopPestering( boolean addMessage) {
    	if( addMessage) {
    		addMessage("Stopping...");
    	}
    	m_pesteringThreadActive = false;
    	if( m_pesteringThread != null) {
    		Thread temp = m_pesteringThread;
    		m_pesteringThread = null;
    		temp.interrupt();
    	}
    	if( addMessage) {
    		addMessage("Stopped");
    	}
    }
    
    private void pester() {
    	String message = m_stockMessages[random.nextInt(m_stockMessages.length)];
    	sendSmsMessage(message);
    	outputMessageToScreen(message);
    	
    	m_lastPester = System.currentTimeMillis();
    }
    
    private void initSmsCallbacks() {
 
        //---when the SMS has been sent---
        registerReceiver(new BroadcastReceiver(){
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                switch (getResultCode())
                {
                    case Activity.RESULT_OK:
                    	m_messageNotSentReason = "";
                    	m_messageSent = true;
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    	m_messageNotSentReason = "Generic failure";
                    	m_messageSent = false;
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        m_messageNotSentReason = "No service";
                    	m_messageSent = false;
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        m_messageNotSentReason = "Internal error - no message sent (null PDU)";
                    	m_messageSent = false;
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        m_messageNotSentReason = "Radio off";
                    	m_messageSent = false;
                        break;
                }
                
                if(!m_messageSent) {
    				requestCheckMessageStatus();
                }
            }
        }, new IntentFilter(SENT));
 
        //---when the SMS has been delivered---
        registerReceiver(new BroadcastReceiver(){
            @Override
            public void onReceive(Context arg0, Intent arg1) {
                switch (getResultCode())
                {
                    case Activity.RESULT_OK:
                    	m_messageDelivered = true;
                        break;
                    case Activity.RESULT_CANCELED:
                    	m_messageDelivered = false;
                        break;                        
                }
                
                if(!m_messageDelivered) {
    				requestCheckMessageStatus();
                }
            }
        }, new IntentFilter(DELIVERED));        
    }
    
    private void sendSmsMessage( String message) {
        
        PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, new Intent(SENT), 0);
        PendingIntent deliveredPI = PendingIntent.getBroadcast(this, 0, new Intent(DELIVERED), 0);
 
    	SmsManager mySMS = SmsManager.getDefault();
    	mySMS.sendTextMessage(m_phone, null, message, sentPI, deliveredPI);
    }
    
    private void outputMessageToScreen(String message) {
    	Message msg = outputMessageToScreenHandler.obtainMessage();
    	Bundle data = msg.getData();
    	data.putString("pest", message);
    	outputMessageToScreenHandler.sendMessage(msg);
    }
    
    private void requestCheckMessageStatus() {
    	warnUserAboutSMSProblemHandler.sendEmptyMessage(0);
    }
    
    private SeekBar.OnSeekBarChangeListener frequencyChangeListener = 
    	new SeekBar.OnSeekBarChangeListener() {
    	
    	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    		setFrequency( progress + m_frequencyOffset);
    	}
    	
    	public void onStartTrackingTouch(SeekBar seekBar) {
    		
    		// record what the frequency was before the user started messing with it
    		//
    		m_lastFrequency = m_frequency;
    	}

    	public void onStopTrackingTouch(SeekBar seekBar) {
    		
    		if( m_pesteringThreadActive) {
    			
	    		// If the frequency decreased so it's now been less since the last pester
	    		// than the new frequency, start afresh
	    		//
    			if( m_frequency < m_lastFrequency) {
            		stopPestering(false);
            		startPestering(false);
    			}
	    		
	    		// If the frequency increased, restart, but the first pester should go out
	    		// at (last pester time) + (frequency)
	    		//
    			else if( m_lastPester > 0 && m_frequency > m_lastFrequency) {
    				m_waitBeforeFirstPester = (int)(System.currentTimeMillis() - m_lastPester) / 1000 - m_frequency;  
    			}
    		}
    	}
    };
    
    private void setFrequency(int frequency) {
		String msg;
		if( frequency < 60) {
			msg = String.format("Send text message every %d sec", frequency);
		} else if( frequency %60 == 0) {
			msg = String.format("Send text message every %d min", frequency / 60);
		} else {
			msg = String.format("Send text message every %d min %d sec", frequency / 60, frequency % 60);
		}
		m_tvFrequency.setText(msg);
		m_frequency = frequency;
    }
}