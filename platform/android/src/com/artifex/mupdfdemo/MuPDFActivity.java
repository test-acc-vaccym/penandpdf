//  -*- compile-command: cd ~/src/android/mupdf/platform/android && ant clean && ~/src/android/android-ndk-r9/ndk-build && ant debug && cp bin/MuPDF-debug.apk /home/cgogolin/Dropbox/galaxynote8/ -*-

package com.artifex.mupdfdemo;

import java.io.InputStream;
import java.io.File;
import java.util.concurrent.Executor;

import com.artifex.mupdfdemo.ReaderView.ViewMapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ViewAnimator;
import android.widget.SearchView;
import android.widget.ShareActionProvider;
import android.preference.PreferenceManager;
import android.app.ActionBar;
import android.app.SearchManager;

import android.text.InputType;



class ThreadPerTaskExecutor implements Executor {
	public void execute(Runnable r) {
		new Thread(r).start();
	}
}

public class MuPDFActivity extends Activity implements FilePicker.FilePickerSupport, SharedPreferences.OnSharedPreferenceChangeListener, SearchView.OnQueryTextListener, SearchView.OnCloseListener
{
        /* The core rendering instance */
	enum TopBarMode {Main, Search, Annot, Delete, More, Accept};
    enum ActionBarMode {Main, Search, Annot, Edit, Delete, More, Accept, Copy};
	enum AcceptMode {Highlight, Underline, StrikeOut, Ink, CopyText};

        private Uri uri;
	private static final float INK_THICKNESS=10f;
        private SearchView searchView = null;
        private String oldQueryText = "";
        private String mQuery = "";
        private ShareActionProvider mShareActionProvider = null;
    
	private final int    OUTLINE_REQUEST=0;
	private final int    PRINT_REQUEST=1;
	private final int    FILEPICK_REQUEST=2;
//        private Menu mMenu;
//    private SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener;
        private MuPDFCore    core;
	private String       mFileName;
	private MuPDFReaderView mDocView;
	private View         mButtonsView;
	private boolean      mButtonsVisible;
	private EditText     mPasswordView;
	private TextView     mFilenameView;
	private SeekBar      mPageSlider;
	private int          mPageSliderRes;
	private TextView     mPageNumberView;
	private TextView     mInfoView;
	private ImageButton  mSearchButton;
	private ImageButton  mReflowButton;
	private ImageButton  mOutlineButton;
	private ImageButton	mMoreButton;
	private TextView     mAnnotTypeText;
	private ImageButton mAnnotButton;
	private ViewAnimator mTopBarSwitcher;
	private ImageButton  mLinkButton;
	private TopBarMode   mTopBarMode = TopBarMode.Main;
    	private ActionBarMode   mActionBarMode = ActionBarMode.Main;
	private AcceptMode   mAcceptMode;
	private ImageButton  mSearchBack;
	private ImageButton  mSearchFwd;
	private EditText     mSearchText;
	private SearchTask   mSearchTask;
	private AlertDialog.Builder mAlertBuilder;
	private boolean    mLinkHighlight = false;
	private final Handler mHandler = new Handler();
	private boolean mAlertsActive= false;
	private boolean mReflow = false;
	private AsyncTask<Void,Void,MuPDFAlert> mAlertTask;
	private AlertDialog mAlertDialog;
	private FilePicker mFilePicker;

	public void createAlertWaiter() {
		mAlertsActive = true;
		// All mupdf library calls are performed on asynchronous tasks to avoid stalling
		// the UI. Some calls can lead to javascript-invoked requests to display an
		// alert dialog and collect a reply from the user. The task has to be blocked
		// until the user's reply is received. This method creates an asynchronous task,
		// the purpose of which is to wait of these requests and produce the dialog
		// in response, while leaving the core blocked. When the dialog receives the
		// user's response, it is sent to the core via replyToAlert, unblocking it.
		// Another alert-waiting task is then created to pick up the next alert.
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
		if (mAlertDialog != null) {
			mAlertDialog.cancel();
			mAlertDialog = null;
		}
		mAlertTask = new AsyncTask<Void,Void,MuPDFAlert>() {

			@Override
			protected MuPDFAlert doInBackground(Void... arg0) {
				if (!mAlertsActive)
					return null;

				return core.waitForAlert();
			}

			@Override
			protected void onPostExecute(final MuPDFAlert result) {
				// core.waitForAlert may return null when shutting down
				if (result == null)
					return;
				final MuPDFAlert.ButtonPressed pressed[] = new MuPDFAlert.ButtonPressed[3];
				for(int i = 0; i < 3; i++)
					pressed[i] = MuPDFAlert.ButtonPressed.None;
				DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						mAlertDialog = null;
						if (mAlertsActive) {
							int index = 0;
							switch (which) {
							case AlertDialog.BUTTON1: index=0; break;
							case AlertDialog.BUTTON2: index=1; break;
							case AlertDialog.BUTTON3: index=2; break;
							}
							result.buttonPressed = pressed[index];
							// Send the user's response to the core, so that it can
							// continue processing.
							core.replyToAlert(result);
							// Create another alert-waiter to pick up the next alert.
							createAlertWaiter();
						}
					}
				};
				mAlertDialog = mAlertBuilder.create();
				mAlertDialog.setTitle(result.title);
				mAlertDialog.setMessage(result.message);
				switch (result.iconType)
				{
				case Error:
					break;
				case Warning:
					break;
				case Question:
					break;
				case Status:
					break;
				}
				switch (result.buttonGroupType)
				{
				case OkCancel:
					mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.cancel), listener);
					pressed[1] = MuPDFAlert.ButtonPressed.Cancel;
				case Ok:
					mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.okay), listener);
					pressed[0] = MuPDFAlert.ButtonPressed.Ok;
					break;
				case YesNoCancel:
					mAlertDialog.setButton(AlertDialog.BUTTON3, getString(R.string.cancel), listener);
					pressed[2] = MuPDFAlert.ButtonPressed.Cancel;
				case YesNo:
					mAlertDialog.setButton(AlertDialog.BUTTON1, getString(R.string.yes), listener);
					pressed[0] = MuPDFAlert.ButtonPressed.Yes;
					mAlertDialog.setButton(AlertDialog.BUTTON2, getString(R.string.no), listener);
					pressed[1] = MuPDFAlert.ButtonPressed.No;
					break;
				}
				mAlertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
					public void onCancel(DialogInterface dialog) {
						mAlertDialog = null;
						if (mAlertsActive) {
							result.buttonPressed = MuPDFAlert.ButtonPressed.None;
							core.replyToAlert(result);
							createAlertWaiter();
						}
					}
				});

				mAlertDialog.show();
			}
		};

		mAlertTask.executeOnExecutor(new ThreadPerTaskExecutor());
	}

	public void destroyAlertWaiter() {
		mAlertsActive = false;
		if (mAlertDialog != null) {
			mAlertDialog.cancel();
			mAlertDialog = null;
		}
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
	}

	private MuPDFCore openFile(String path)
	{
		int lastSlashPos = path.lastIndexOf('/');
		mFileName = new String(lastSlashPos == -1
					? path
					: path.substring(lastSlashPos+1));
		System.out.println("Trying to open "+path);
		try
		{
			core = new MuPDFCore(this, path);
			// New file: drop the old outline data
			OutlineActivityData.set(null);
		}
		catch (Exception e)
		{
			System.out.println(e);
			return null;
		}
		return core;
	}

	private MuPDFCore openBuffer(byte buffer[])
	{
		System.out.println("Trying to open byte buffer");
		try
		{
			core = new MuPDFCore(this, buffer);
			// New file: drop the old outline data
			OutlineActivityData.set(null);
		}
		catch (Exception e)
		{
			System.out.println(e);
			return null;
		}
		return core;
	}


    @Override
    public void onNewIntent(Intent intent) { 
        // if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
        //     if(mQuery != intent.getStringExtra(SearchManager.QUERY)) //For some reason we sometimes recieve two search intents in rapid succession
        //     {
        //         mQuery = intent.getStringExtra(SearchManager.QUERY);
        //         search(1);
        //     }
        // }   
    }

    
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

                    //Set default preferences on first start
                PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
                    //And register a OnSharedPreferenceChangeListener
                // mOnSharedPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                //         public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                //             setPreferencesInCore();
                //                 //mDocView.resetupChildren();//This should be used to set preferences in page views...
                //         }
                //     };
                //PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
                PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
                
		mAlertBuilder = new AlertDialog.Builder(this);

                    //Get filename from saved instance
		if (core == null) {
			core = (MuPDFCore)getLastNonConfigurationInstance();

			if (savedInstanceState != null && savedInstanceState.containsKey("FileName")) {
				mFileName = savedInstanceState.getString("FileName");
			}
		}
                
		if (core == null) {
			Intent intent = getIntent();
			byte buffer[] = null;
			if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				uri = intent.getData();
				if (uri.toString().startsWith("content://")) {
					// Handle view requests from the Transformer Prime's file manager
					// Hopefully other file managers will use this same scheme, if not
					// using explicit paths.
					Cursor cursor = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
					if (cursor.moveToFirst()) {
						String str = cursor.getString(0);
						String reason = null;
						if (str == null) {
							try {
								InputStream is = getContentResolver().openInputStream(uri);
								int len = is.available();
								buffer = new byte[len];
								is.read(buffer, 0, len);
								is.close();
							}
							catch (java.lang.OutOfMemoryError e)
							{
								System.out.println("Out of memory during buffer reading");
								reason = e.toString();
							}
							catch (Exception e) {
								reason = e.toString();
							}
							if (reason != null)
							{
								buffer = null;
								Resources res = getResources();
								AlertDialog alert = mAlertBuilder.create();
								setTitle(String.format(res.getString(R.string.cannot_open_document_Reason), reason));
								alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
										new DialogInterface.OnClickListener() {
											public void onClick(DialogInterface dialog, int which) {
												finish();
											}
										});
								alert.show();
								return;
							}
						} else {
							uri = Uri.parse(str);
						}
					}
				}
				if (buffer != null) {
					core = openBuffer(buffer);
				} else {
					core = openFile(Uri.decode(uri.getEncodedPath()));
				}
				SearchTaskResult.set(null);
			}
			if (core != null && core.needsPassword()) {
				requestPassword(savedInstanceState);
				return;
			}
			if (core != null && core.countPages() == 0)
			{
				core = null;
			}
		}
		if (core == null)
		{
			AlertDialog alert = mAlertBuilder.create();
			alert.setTitle(R.string.cannot_open_document);
			alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			alert.show();
			return;
		}
                if (core != null) 
                {
                    setPreferencesInCore();
                }
		createUI(savedInstanceState);
	}

    @Override
    public boolean onCreateOptionsMenu(Menu menu) //Inflates the options menu
    {
//        mMenu = menu;
        MenuInflater inflater = getMenuInflater();
        switch (mActionBarMode)
        {
            case Main:
                inflater.inflate(R.menu.main_menu, menu);
                
                    // Locate MenuItem with ShareActionProvider, fetch and store ShareActionProvider, determine file name and set up the ShareActionProvider
               if (mShareActionProvider == null)
               {
                    MenuItem shareItem = menu.findItem(R.id.menu_share);
                    mShareActionProvider = (ShareActionProvider) shareItem.getActionProvider();
                    Intent shareIntent = new Intent();
                    shareIntent.setAction(Intent.ACTION_SEND);
                    shareIntent.setType("plain/text");
                    shareIntent.setType("*/*");
//                    shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(uri.getEncodedPath()))); //Causes crash on orientation change
                    if (mShareActionProvider != null) mShareActionProvider.setShareIntent(shareIntent);
               }
                break;
            case Annot:
            case Edit:
            case Copy:
                inflater.inflate(R.menu.annot_menu, menu);
                MenuItem undoButton = menu.findItem(R.id.menu_undo);
                undoButton.setEnabled(false).setVisible(false);
                break;
            case Search:
                inflater.inflate(R.menu.search_menu, menu);
                    // Associate searchable configuration with the SearchView
                SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
                searchView = (SearchView) menu.findItem(R.id.menu_search_box).getActionView();
                searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
                searchView.setIconified(false);
                searchView.setOnCloseListener(this); //Implemented in: public void onClose(View view)
                searchView.setOnQueryTextListener(this); //Implemented in: public boolean onQueryTextChange(String query) and public boolean onQueryTextSubmit(String query)
            default:
        }
        return true;
    }

    @Override
    public boolean onClose() //X button in search box
    {
//        oldQueryText = "";
//        searchView.setQuery("",false);
        // mActionBarMode = ActionBarMode.Main;
        // invalidateOptionsMenu();

        SearchTaskResult.set(null);
            // Make the ReaderView act on the change to mSearchTaskResult
            // via overridden onChildSetup method.
        mDocView.resetupChildren();
        return false;
    }
    
    @Override
    public boolean onQueryTextChange(String query) //For search
    { //This is a hacky way to determine when the user has reset the text field with the X button 
        if ( query.length() == 0 && oldQueryText.length() > 1) {
            SearchTaskResult.set(null);
            mDocView.resetupChildren();
        }
        oldQueryText = query;
        return false;
    }

    @Override 
    public boolean onQueryTextSubmit(String query) //For search
    {
        if(mQuery != query)
        {
                // showInfo("Searching for "+query);
            mQuery = query;
            search(1);
        }
        return true; //We handle this here and don't want to call onNewIntent()
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) //Handel clicks in the options menu 
    {
        switch (item.getItemId()) 
        {
            case R.id.menu_settings:
                Intent intent = new Intent(this,SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.menu_draw:
                OnInkButtonClick(mButtonsView);
                mActionBarMode = ActionBarMode.Annot;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_highlight:
                OnHighlightButtonClick(mButtonsView);
                mActionBarMode = ActionBarMode.Annot;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_underline:
                OnUnderlineButtonClick(mButtonsView);
                mActionBarMode = ActionBarMode.Annot;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_strikeout:
                OnStrikeOutButtonClick(mButtonsView);
                mActionBarMode = ActionBarMode.Annot;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_cancel:
                switch (mActionBarMode) {
                    case Annot:
                    case Copy:
                        OnCancelAcceptButtonClick(mButtonsView);
                        break;
                    case Edit:
                        OnDeleteButtonClick(mButtonsView);
                        break;
                    case Search:
                        SearchTaskResult.set(null);
                        mDocView.resetupChildren();
                        break;
                }
                mActionBarMode = ActionBarMode.Main;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_accept:
                switch (mActionBarMode) {
                    case Annot:
                    case Copy:
                        OnAcceptButtonClick(mButtonsView);
                        break;
                    case Edit:
                        MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
                        if (pageView != null) pageView.deselectAnnotation();
                }
                mActionBarMode = ActionBarMode.Main;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_print:
                OnPrintButtonClick(mButtonsView);
//                showInfo("uri: "+uri.toString());
                return true;
            case R.id.menu_copytext:
                mActionBarMode = ActionBarMode.Copy;
                invalidateOptionsMenu();
                OnCopyTextButtonClick(mButtonsView);
                return true;
            case R.id.menu_search:
                mActionBarMode = ActionBarMode.Search;
                invalidateOptionsMenu();
                return true;
            case R.id.menu_next:
                if (mQuery != "") search(1);
                return true;
            case R.id.menu_previous:
                if (mQuery != "") search(-1);
                return true;
            case R.id.menu_save:
                return true;
            case R.id.menu_gotopage:
                showGoToPageDialoge();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    public void requestPassword(final Bundle savedInstanceState) {
		mPasswordView = new EditText(this);
		mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		mPasswordView.setTransformationMethod(new PasswordTransformationMethod());

		AlertDialog alert = mAlertBuilder.create();
		alert.setTitle(R.string.enter_password);
		alert.setView(mPasswordView);
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
				new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (core.authenticatePassword(mPasswordView.getText().toString())) {
					createUI(savedInstanceState);
				} else {
					requestPassword(savedInstanceState);
				}
			}
		});
		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
				new DialogInterface.OnClickListener() {

			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		alert.show();
	}


    private void showGoToPageDialoge() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine();
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_gotopage_title)
            .setPositiveButton(R.string.dialog_gotopage_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                            // User clicked OK button
                        int pageNumber = Integer.parseInt(input.getText().toString());
                        mDocView.setDisplayedViewIndex(pageNumber == 0 ? 0 : pageNumber -1 );
                    }
                })
            .setNegativeButton(R.string.dialog_gotopage_cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                            // User cancelled the dialog
                    }
                })
            .setView(input)
            .show();
    }
    
        public void createUI(Bundle savedInstanceState) {
            if (core == null)
                return;
            
		// Now create the UI.
		// First create the document view
		mDocView = new MuPDFReaderView(this) {
			@Override
			protected void onMoveToChild(int i) {
				if (core == null)
					return;
				mPageNumberView.setText(String.format("%d / %d", i + 1,
						core.countPages()));
				mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);
				mPageSlider.setProgress(i * mPageSliderRes);
				super.onMoveToChild(i);
			}

			@Override
			protected void onTapMainDocArea() {
                            if (mActionBarMode == ActionBarMode.Edit) 
                            {
                                mActionBarMode = ActionBarMode.Main;
                                invalidateOptionsMenu();
                            }
			}

			@Override
			protected void onDocMotion() {

			}

			@Override
			protected void onHit(Hit item) {
                            if (item == Hit.Annotation) {
                                mTopBarMode = TopBarMode.Annot;
                                mActionBarMode = ActionBarMode.Edit;
                                invalidateOptionsMenu();
                            }
                            if (item == Hit.Nothing) {
                                mTopBarMode = TopBarMode.Main;
                                mActionBarMode = ActionBarMode.Main;
                                invalidateOptionsMenu();
                            }
			}
		};
		mDocView.setAdapter(new MuPDFPageAdapter(this, this, core));

                    //Enable link highlighting by default
                mDocView.setLinksEnabled(true);
                                
		mSearchTask = new SearchTask(this, core) {
			@Override
			protected void onTextFound(SearchTaskResult result) {
				SearchTaskResult.set(result);
				// Ask the ReaderView to move to the resulting page
				mDocView.setDisplayedViewIndex(result.pageNumber);
				// Make the ReaderView act on the change to SearchTaskResult
				// via overridden onChildSetup method.
				mDocView.resetupChildren();
			}
		};

		// Make the buttons overlay, and store all its
		// controls in variables
		makeButtonsView();

		// Set up the page slider
		int smax = Math.max(core.countPages()-1,1);
		mPageSliderRes = ((10 + smax - 1)/smax) * 2;

		// Set the file-name text
		mFilenameView.setText(mFileName);

		// Activate the seekbar
		mPageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onStopTrackingTouch(SeekBar seekBar) {
				mDocView.setDisplayedViewIndex((seekBar.getProgress()+mPageSliderRes/2)/mPageSliderRes);
			}

			public void onStartTrackingTouch(SeekBar seekBar) {}

			public void onProgressChanged(SeekBar seekBar, int progress,
					boolean fromUser) {
				updatePageNumView((progress+mPageSliderRes/2)/mPageSliderRes);
			}
		});

		// Activate the search-preparing button
		mSearchButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				searchModeOn();
			}
		});

		// Activate the reflow button
		mReflowButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				toggleReflow();
			}
		});

		if (core.fileFormat().startsWith("PDF"))
		{
			mAnnotButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					mTopBarMode = TopBarMode.Annot;
					mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
				}
			});
		}
		else
		{
			mAnnotButton.setVisibility(View.GONE);
		}

		// Search invoking buttons are disabled while there is no text specified
		mSearchBack.setEnabled(false);
		mSearchFwd.setEnabled(false);
		mSearchBack.setColorFilter(Color.argb(255, 128, 128, 128));
		mSearchFwd.setColorFilter(Color.argb(255, 128, 128, 128));

		// React to interaction with the text widget
		mSearchText.addTextChangedListener(new TextWatcher() {

			public void afterTextChanged(Editable s) {
				boolean haveText = s.toString().length() > 0;
				setButtonEnabled(mSearchBack, haveText);
				setButtonEnabled(mSearchFwd, haveText);

				// Remove any previous search results
				if (SearchTaskResult.get() != null && !mSearchText.getText().toString().equals(SearchTaskResult.get().txt)) {
					SearchTaskResult.set(null);
					mDocView.resetupChildren();
				}
			}
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {}
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {}
		});

		//React to Done button on keyboard
		mSearchText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_DONE)
					search(1);
				return false;
			}
		});

		mSearchText.setOnKeyListener(new View.OnKeyListener() {
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER)
					search(1);
				return false;
			}
		});

		// Activate search invoking buttons
		mSearchBack.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(-1);
			}
		});
		mSearchFwd.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(1);
			}
		});

		mLinkButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				setLinkHighlight(!mLinkHighlight);
			}
		});

		if (core.hasOutline()) {
			mOutlineButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					OutlineItem outline[] = core.getOutline();
					if (outline != null) {
						OutlineActivityData.get().items = outline;
						Intent intent = new Intent(MuPDFActivity.this, OutlineActivity.class);
						startActivityForResult(intent, OUTLINE_REQUEST);
					}
				}
			});
		} else {
			mOutlineButton.setVisibility(View.GONE);
		}

		// Reenstate last state if it was recorded
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		mDocView.setDisplayedViewIndex(prefs.getInt("page"+mFileName, 0));

		// if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false)) 
                // {
                //         showButtons();
                // }
                

		if(savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
			searchModeOn();

		if(savedInstanceState != null && savedInstanceState.getBoolean("ReflowMode", false))
			reflowModeSet(true);

		// Stick the document view and the buttons overlay into a parent view
		RelativeLayout layout = new RelativeLayout(this);
		layout.addView(mDocView);
//		layout.addView(mButtonsView);
		setContentView(layout);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case OUTLINE_REQUEST:
			if (resultCode >= 0)
				mDocView.setDisplayedViewIndex(resultCode);
			break;
		case PRINT_REQUEST:
			if (resultCode == RESULT_CANCELED)
				showInfo(getString(R.string.print_failed));
			break;
		case FILEPICK_REQUEST:
			if (mFilePicker != null && resultCode == RESULT_OK)
				mFilePicker.onPick(data.getData());
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	public Object onRetainNonConfigurationInstance()
	{
		MuPDFCore mycore = core;
		core = null;
		return mycore;
	}

	private void reflowModeSet(boolean reflow)
	{
		mReflow = reflow;
		mDocView.setAdapter(mReflow ? new MuPDFReflowAdapter(this, core) : new MuPDFPageAdapter(this, this, core));
		mReflowButton.setColorFilter(mReflow ? Color.argb(0xFF, 172, 114, 37) : Color.argb(0xFF, 255, 255, 255));
		setButtonEnabled(mAnnotButton, !reflow);
		setButtonEnabled(mSearchButton, !reflow);
		if (reflow) setLinkHighlight(false);
		setButtonEnabled(mLinkButton, !reflow);
		setButtonEnabled(mMoreButton, !reflow);
		mDocView.refresh(mReflow);
	}

	private void toggleReflow() {
		reflowModeSet(!mReflow);
//		showInfo(mReflow ? getString(R.string.entering_reflow_mode) : getString(R.string.leaving_reflow_mode));
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (mFileName != null && mDocView != null) {
			outState.putString("FileName", mFileName);

			// Store current page in the prefs against the file name,
			// so that we can pick it up each time the file is loaded
			// Other info is needed only for screen-orientation change,
			// so it can go in the bundle
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mFileName, mDocView.getDisplayedViewIndex());
			edit.commit();
		}

		if (!mButtonsVisible)
			outState.putBoolean("ButtonsHidden", true);

		if (mTopBarMode == TopBarMode.Search)
			outState.putBoolean("SearchMode", true);

		if (mReflow)
			outState.putBoolean("ReflowMode", true);
	}

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        setPreferencesInCore();
            //mDocView.resetupChildren();//This should be used to set preferences in page views...
    }

    private void setPreferencesInCore(){
        if (core != null) 
        {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);   
                //Set ink thickness in core
            float inkThickness = Float.parseFloat(sharedPref.getString(SettingsActivity.PREF_INK_THICKNESS, Float.toString(INK_THICKNESS)));
            core.setInkThickness(inkThickness*0.45f); // I have no idea whre the 0.45 comes from....               
            
                //Set colors in core
            int colorNumber;                    
            colorNumber = Integer.parseInt(sharedPref.getString(SettingsActivity.PREF_INK_COLOR, "0" ));
            core.setInkColor(ColorPalette.getR(colorNumber), ColorPalette.getG(colorNumber), ColorPalette.getB(colorNumber));
            colorNumber = Integer.parseInt(sharedPref.getString(SettingsActivity.PREF_HIGHLIGHT_COLOR, "0" ));
            core.setHighlightColor(ColorPalette.getR(colorNumber), ColorPalette.getG(colorNumber), ColorPalette.getB(colorNumber));
            colorNumber = Integer.parseInt(sharedPref.getString(SettingsActivity.PREF_UNDERLINE_COLOR, "0" ));
            core.setUnderlineColor(ColorPalette.getR(colorNumber), ColorPalette.getG(colorNumber), ColorPalette.getB(colorNumber));
            colorNumber = Integer.parseInt(sharedPref.getString(SettingsActivity.PREF_STRIKEOUT_COLOR, "0" ));
            core.setStrikeoutColor(ColorPalette.getR(colorNumber), ColorPalette.getG(colorNumber), ColorPalette.getB(colorNumber));
        }
    }
    
	@Override
	protected void onPause() {
		super.onPause();
//                PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this); //Do not unregister here otherwise we miss changes while settings activity in forground
                
		if (mSearchTask != null)
			mSearchTask.stop();

		if (mFileName != null && mDocView != null) {
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mFileName, mDocView.getDisplayedViewIndex());
			edit.commit();
		}
	}

    @Override
    protected void onResume() {
        super.onResume();
//        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }
    
	public void onDestroy()
	{
		mDocView.applyToChildren(new ReaderView.ViewMapper() {
			void applyToView(View view) {
				((MuPDFView)view).releaseBitmaps();
			}
		});

                SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

		if (core != null)
                {
                    if(sharedPref.getBoolean(SettingsActivity.PREF_SAVE_ON_DESTROY, true)) core.save();                    
                    core.onDestroy(); //Causes problems on configuration change!!!
                }
		if (mAlertTask != null) {
			mAlertTask.cancel(true);
			mAlertTask = null;
		}
		core = null;
		super.onDestroy();
	}

	private void setButtonEnabled(ImageButton button, boolean enabled) {
		button.setEnabled(enabled);
		button.setColorFilter(enabled ? Color.argb(255, 255, 255, 255):Color.argb(255, 128, 128, 128));
	}

	private void setLinkHighlight(boolean highlight) {
		mLinkHighlight = highlight;
		// LINK_COLOR tint
		mLinkButton.setColorFilter(highlight ? Color.argb(0xFF, 172, 114, 37) : Color.argb(0xFF, 255, 255, 255));
		// Inform pages of the change.
		mDocView.setLinksEnabled(highlight);
	}

	private void showButtons() {
		if (core == null)
			return;
		if (!mButtonsVisible) {
			mButtonsVisible = true;
			// Update page number text and slider
			int index = mDocView.getDisplayedViewIndex();
			updatePageNumView(index);
			mPageSlider.setMax((core.countPages()-1)*mPageSliderRes);
			mPageSlider.setProgress(index*mPageSliderRes);
			if (mTopBarMode == TopBarMode.Search) {
				mSearchText.requestFocus();
				showKeyboard();
			}

			Animation anim = new TranslateAnimation(0, 0, -mTopBarSwitcher.getHeight(), 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mTopBarSwitcher.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {}
			});
			mTopBarSwitcher.startAnimation(anim);

			anim = new TranslateAnimation(0, 0, mPageSlider.getHeight(), 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPageSlider.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mPageNumberView.setVisibility(View.VISIBLE);
				}
			});
			mPageSlider.startAnimation(anim);
		}
	}

	private void hideButtons() {
		if (mButtonsVisible) {
			mButtonsVisible = false;
			hideKeyboard();

			Animation anim = new TranslateAnimation(0, 0, 0, -mTopBarSwitcher.getHeight());
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mTopBarSwitcher.setVisibility(View.INVISIBLE);
				}
			});
			mTopBarSwitcher.startAnimation(anim);

			anim = new TranslateAnimation(0, 0, 0, mPageSlider.getHeight());
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPageNumberView.setVisibility(View.INVISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mPageSlider.setVisibility(View.INVISIBLE);
				}
			});
			mPageSlider.startAnimation(anim);
		}
	}

	private void searchModeOn() {
		if (mTopBarMode != TopBarMode.Search) {
			mTopBarMode = TopBarMode.Search;
			//Focus on EditTextWidget
			mSearchText.requestFocus();
			showKeyboard();
			mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		}
	}

	private void searchModeOff() {
		if (mTopBarMode == TopBarMode.Search) {
			mTopBarMode = TopBarMode.Main;
			hideKeyboard();
			mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
			SearchTaskResult.set(null);
			// Make the ReaderView act on the change to mSearchTaskResult
			// via overridden onChildSetup method.
			mDocView.resetupChildren();
		}
	}

	private void updatePageNumView(int index) {
		if (core == null)
			return;
		mPageNumberView.setText(String.format("%d / %d", index+1, core.countPages()));
	}

	private void printDoc() {
		if (!core.fileFormat().startsWith("PDF")) {
			showInfo(getString(R.string.format_currently_not_supported));
			return;
		}

		Intent myIntent = getIntent();
		Uri docUri = myIntent != null ? myIntent.getData() : null;

		if (docUri == null) {
			showInfo(getString(R.string.print_failed));
		}

		if (docUri.getScheme() == null)
			docUri = Uri.parse("file://"+docUri.toString());

		Intent printIntent = new Intent(this, PrintDialogActivity.class);
		printIntent.setDataAndType(docUri, "aplication/pdf");
		printIntent.putExtra("title", mFileName);
		startActivityForResult(printIntent, PRINT_REQUEST);
	}


    	private void shareDoc() {
		Intent myIntent = getIntent();
		Uri docUri = myIntent != null ? myIntent.getData() : null;

		if (docUri == null) {
                        //showInfo(getString(R.string.print_failed)); //???
		}

		if (docUri.getScheme() == null)
			docUri = Uri.parse("file://"+docUri.toString());

                    //???
	}

	private void showInfo(String message) {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
	}

	private void makeButtonsView() {
		mButtonsView = getLayoutInflater().inflate(R.layout.buttons,null);
		mFilenameView = (TextView)mButtonsView.findViewById(R.id.docNameText);
		mPageSlider = (SeekBar)mButtonsView.findViewById(R.id.pageSlider);
		mPageNumberView = (TextView)mButtonsView.findViewById(R.id.pageNumber);
		mInfoView = (TextView)mButtonsView.findViewById(R.id.info);
		mSearchButton = (ImageButton)mButtonsView.findViewById(R.id.searchButton);
		mReflowButton = (ImageButton)mButtonsView.findViewById(R.id.reflowButton);
		mOutlineButton = (ImageButton)mButtonsView.findViewById(R.id.outlineButton);
		mAnnotButton = (ImageButton)mButtonsView.findViewById(R.id.editAnnotButton);
		mAnnotTypeText = (TextView)mButtonsView.findViewById(R.id.annotType);
		mTopBarSwitcher = (ViewAnimator)mButtonsView.findViewById(R.id.switcher);
		mSearchBack = (ImageButton)mButtonsView.findViewById(R.id.searchBack);
		mSearchFwd = (ImageButton)mButtonsView.findViewById(R.id.searchForward);
		mSearchText = (EditText)mButtonsView.findViewById(R.id.searchText);
		mLinkButton = (ImageButton)mButtonsView.findViewById(R.id.linkButton);
		mMoreButton = (ImageButton)mButtonsView.findViewById(R.id.moreButton);
		mTopBarSwitcher.setVisibility(View.INVISIBLE);
		mPageNumberView.setVisibility(View.INVISIBLE);
		mInfoView.setVisibility(View.INVISIBLE);
		mPageSlider.setVisibility(View.INVISIBLE);
	}

	public void OnMoreButtonClick(View v) {
		mTopBarMode = TopBarMode.More;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnCancelMoreButtonClick(View v) {
		mTopBarMode = TopBarMode.Main;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnPrintButtonClick(View v) {
		printDoc();
	}

	public void OnCopyTextButtonClick(View v) {
		mTopBarMode = TopBarMode.Accept;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mAcceptMode = AcceptMode.CopyText;
		mDocView.setMode(MuPDFReaderView.Mode.Selecting);
		mAnnotTypeText.setText(getString(R.string.copy_text));
		showInfo(getString(R.string.select_text));
	}

	public void OnEditAnnotButtonClick(View v) {
		mTopBarMode = TopBarMode.Annot;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnCancelAnnotButtonClick(View v) {
		mTopBarMode = TopBarMode.More;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnHighlightButtonClick(View v) {
		mTopBarMode = TopBarMode.Accept;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mAcceptMode = AcceptMode.Highlight;
		mDocView.setMode(MuPDFReaderView.Mode.Selecting);
		mAnnotTypeText.setText(R.string.highlight);
//		showInfo(getString(R.string.select_text));
	}

	public void OnUnderlineButtonClick(View v) {
		mTopBarMode = TopBarMode.Accept;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mAcceptMode = AcceptMode.Underline;
		mDocView.setMode(MuPDFReaderView.Mode.Selecting);
		mAnnotTypeText.setText(R.string.underline);
//		showInfo(getString(R.string.select_text));
	}

	public void OnStrikeOutButtonClick(View v) {
		mTopBarMode = TopBarMode.Accept;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mAcceptMode = AcceptMode.StrikeOut;
		mDocView.setMode(MuPDFReaderView.Mode.Selecting);
		mAnnotTypeText.setText(R.string.strike_out);
//		showInfo(getString(R.string.select_text));
	}

	public void OnInkButtonClick(View v) {
		mTopBarMode = TopBarMode.Accept;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mAcceptMode = AcceptMode.Ink;
		mDocView.setMode(MuPDFReaderView.Mode.Drawing);
		mAnnotTypeText.setText(R.string.ink);
//		showInfo(getString(R.string.draw_annotation));
	}

	public void OnCancelAcceptButtonClick(View v) {
		MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
		if (pageView != null) {
			pageView.deselectText();
			pageView.cancelDraw();
		}
		mDocView.setMode(MuPDFReaderView.Mode.Viewing);
		switch (mAcceptMode) {
		case CopyText:
			mTopBarMode = TopBarMode.More;
			break;
		default:
			mTopBarMode = TopBarMode.Annot;
			break;
		}
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnAcceptButtonClick(View v) {
		MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
		boolean success = false;
		switch (mAcceptMode) {
		case CopyText:
			if (pageView != null)
				success = pageView.copySelection();
			mTopBarMode = TopBarMode.More;
			showInfo(success?getString(R.string.copied_to_clipboard):getString(R.string.no_text_selected));
			break;

		case Highlight:
			if (pageView != null)
				success = pageView.markupSelection(Annotation.Type.HIGHLIGHT);
			mTopBarMode = TopBarMode.Annot;
			// if (!success)
			// 	showInfo(getString(R.string.no_text_selected));
			break;

		case Underline:
			if (pageView != null)
				success = pageView.markupSelection(Annotation.Type.UNDERLINE);
			mTopBarMode = TopBarMode.Annot;
			// if (!success)
			// 	showInfo(getString(R.string.no_text_selected));
			break;

		case StrikeOut:
			if (pageView != null)
				success = pageView.markupSelection(Annotation.Type.STRIKEOUT);
			mTopBarMode = TopBarMode.Annot;
			// if (!success)
			// 	showInfo(getString(R.string.no_text_selected));
			break;

		case Ink:
			if (pageView != null)
				success = pageView.saveDraw();
			mTopBarMode = TopBarMode.Annot;
			// if (!success)
                        //     showInfo(getString(R.string.nothing_to_save));
			break;
		}
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
		mDocView.setMode(MuPDFReaderView.Mode.Viewing);
	}

	public void OnCancelSearchButtonClick(View v) {
		searchModeOff();
	}

	public void OnDeleteButtonClick(View v) {
		MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
		if (pageView != null)
			pageView.deleteSelectedAnnotation();
		mTopBarMode = TopBarMode.Annot;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	public void OnCancelDeleteButtonClick(View v) {
		MuPDFView pageView = (MuPDFView) mDocView.getDisplayedView();
		if (pageView != null)
			pageView.deselectAnnotation();
		mTopBarMode = TopBarMode.Annot;
		mTopBarSwitcher.setDisplayedChild(mTopBarMode.ordinal());
	}

	private void showKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(mSearchText, 0);
	}

	private void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}

        private void search(int direction) {
		hideKeyboard();
                
		int displayPage = mDocView.getDisplayedViewIndex();
		SearchTaskResult r = SearchTaskResult.get();
		int searchPage = r != null ? r.pageNumber : -1;
		mSearchTask.go(mQuery, direction, displayPage, searchPage);
	}

	@Override
	public boolean onSearchRequested() {
		if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOn();
		}
		return super.onSearchRequested();
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOff();
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	protected void onStart() {
		if (core != null)
		{
			core.startAlerts();
			createAlertWaiter();
		}

		super.onStart();
	}

	@Override
	protected void onStop() {
		if (core != null)
		{
			destroyAlertWaiter();
			core.stopAlerts();
		}

		super.onStop();
	}

	@Override
	public void onBackPressed() {
		if (core.hasChanges()) {
			DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					if (which == AlertDialog.BUTTON_POSITIVE)
                                            if(core.save()==0)
                                                showInfo(getString(R.string.successfully_saved));
                                            else
                                                showInfo(getString(R.string.error_saveing));
					finish();
				}
			};
			AlertDialog alert = mAlertBuilder.create();
			alert.setTitle("MuPDF");
			alert.setMessage(getString(R.string.document_has_changes_save_them_));
			alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.yes), listener);
			alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.no), listener);
			alert.show();
		} else {
			super.onBackPressed();
		}
	}

	@Override
	public void performPickFor(FilePicker picker) {
		mFilePicker = picker;
		Intent intent = new Intent(this, ChoosePDFActivity.class);
		startActivityForResult(intent, FILEPICK_REQUEST);
	}
}
