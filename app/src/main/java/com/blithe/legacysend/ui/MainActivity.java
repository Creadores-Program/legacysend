package com.blithe.legacysend.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.blithe.legacysend.LegacySendApp;
import com.blithe.legacysend.R;
import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;
import com.blithe.legacysend.server.IncomingSession;
import com.blithe.legacysend.storage.StorageUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements LegacySendApp.UiListener {

    private LegacySendApp app;
    private TextView deviceName;
    private TextView serviceStatus;
    private Button serviceButton;
    private LinearLayout selectedFilesContainer;
    private LinearLayout devicesContainer;
    private final List<TransferFile> selectedFiles = new ArrayList<TransferFile>();
    private Dialog progressDialog;
    private TextView progressTitle;
    private TextView progressFile;
    private TextView progressPath;
    private ProgressBar progressBar;
    private Button cancelTransfer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (LegacySendApp) getApplication();
        buildUi();
        app.setUiListener(this);
        app.startReceiving();
        importSharedFiles(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        importSharedFiles(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.setUiListener(this);
    }

    @Override
    protected void onPause() {
        app.setUiListener(null);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(getString(R.string.app_name), 26, Color.rgb(25, 55, 90));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());
        TextView subtitle = text(getString(R.string.app_subtitle), 14, Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, matchWrap());
        root.addView(space(18));

        root.addView(section(getString(R.string.section_local_device)));
        deviceName = text(getString(R.string.status_initializing_identity), 17, Color.BLACK);
        root.addView(deviceName, matchWrap());
        serviceStatus = text(getString(R.string.status_service_stopped), 14, Color.DKGRAY);
        root.addView(serviceStatus, matchWrap());

        LinearLayout serviceActions = horizontal();
        serviceButton = button(getString(R.string.btn_stop_receiving));
        serviceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (getString(R.string.btn_stop_receiving).contentEquals(serviceButton.getText())) {
                    app.stopReceiving();
                } else {
                    app.startReceiving();
                }
            }
        });
        Button refresh = button(getString(R.string.btn_rediscover));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { app.refreshDiscovery(); }
        });
        serviceActions.addView(serviceButton, weighted());
        serviceActions.addView(refresh, weighted());
        root.addView(serviceActions, matchWrap());

        root.addView(space(18));
        root.addView(section(getString(R.string.section_select_files)));
        Button choose = button(getString(R.string.btn_choose_files));
        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openFilePicker(); }
        });
        root.addView(choose, matchWrap());
        selectedFilesContainer = new LinearLayout(this);
        selectedFilesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(selectedFilesContainer, matchWrap());
        renderSelectedFiles();

        root.addView(space(18));
        root.addView(section(getString(R.string.section_nearby_devices)));
        TextView hint = text(getString(R.string.hint_select_device_to_send), 13, Color.DKGRAY);
        root.addView(hint, matchWrap());
        devicesContainer = new LinearLayout(this);
        devicesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(devicesContainer, matchWrap());
        renderDevices(new ArrayList<DeviceInfo>());

        setContentView(scroll);
    }

    private void openFilePicker() {
        openLegacyDirectory(legacyStorageRoot());
    }

    private void openLegacyDirectory(final File directory) {
        File[] listed = directory.listFiles();
        if (listed == null) {
            Toast.makeText(this, getString(R.string.error_read_dir_permission), Toast.LENGTH_SHORT).show();
            return;
        }
        final List<File> entries = new ArrayList<File>(Arrays.asList(listed));
        Collections.sort(entries, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            File file = entries.get(i);
            labels[i] = file.isDirectory() ? getString(R.string.prefix_folder) + " " + file.getName()
                    : file.getName() + "  ·  " + formatSize(file.length());
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_select_file) + "\n" + directory.getAbsolutePath())
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        File selected = entries.get(which);
                        if (selected.isDirectory()) {
                            openLegacyDirectory(selected);
                        } else if (selected.isFile() && selected.canRead()) {
                            for (TransferFile existing : selectedFiles) {
                                if (existing.getUri().equals(Uri.fromFile(selected))) return;
                            }
                            selectedFiles.add(StorageUtils.describe(MainActivity.this, selected));
                            renderSelectedFiles();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.msg_file_added), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.error_cannot_read_file_simple), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(getString(R.string.btn_cancel), null);
        File root = legacyStorageRoot();
        File parent = directory.getParentFile();
        if (parent != null && !directory.equals(root)) {
            builder.setNeutralButton(getString(R.string.btn_parent_directory), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    openLegacyDirectory(directory.getParentFile());
                }
            });
        }
        builder.show();
    }

    private File legacyStorageRoot() {
        File root = Environment.getExternalStorageDirectory();
        // Compatibilidad para firmwares antiguos donde se mapea el directorio raíz a Download
        File parent = root.getParentFile();
        if (Environment.DIRECTORY_DOWNLOADS.equalsIgnoreCase(root.getName())
                && parent != null && parent.canRead()) {
            return parent;
        }
        return root;
    }

    private void addSelectedUri(Uri uri) {
        if (uri == null) return;
        for (TransferFile file : selectedFiles) if (uri.equals(file.getUri())) return;
        selectedFiles.add(StorageUtils.describe(this, getContentResolver(), uri));
    }

    private void importSharedFiles(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;

        int previousCount = selectedFiles.size();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Uri uri : streams) addSelectedUri(uri);
            }
        } else {
            addSelectedUri((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                addSelectedUri(clip.getItemAt(index).getUri());
            }
        }
        renderSelectedFiles();
        int importedCount = selectedFiles.size() - previousCount;
        if (importedCount > 0) {
            Toast.makeText(this, getString(R.string.msg_shared_files_added, importedCount), Toast.LENGTH_SHORT).show();
        } else if (selectedFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_shareable_files), Toast.LENGTH_SHORT).show();
        }
    }

    private void renderSelectedFiles() {
        selectedFilesContainer.removeAllViews();
        if (selectedFiles.isEmpty()) {
            selectedFilesContainer.addView(text(getString(R.string.msg_no_files_selected_yet), 14, Color.GRAY), matchWrap());
            return;
        }
        for (final TransferFile file : new ArrayList<TransferFile>(selectedFiles)) {
            LinearLayout row = horizontal();
            TextView info = text(file.getFileName() + "\n" + formatSize(file.getSize()), 14, Color.BLACK);
            Button remove = button(getString(R.string.btn_remove));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selectedFiles.remove(file);
                    renderSelectedFiles();
                }
            });
            row.addView(info, weighted());
            row.addView(remove, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT));
            selectedFilesContainer.addView(row, matchWrap());
        }
    }

    private void renderDevices(List<DeviceInfo> devices) {
        devicesContainer.removeAllViews();
        if (devices.isEmpty()) {
            devicesContainer.addView(text(getString(R.string.msg_no_devices_found), 14, Color.GRAY), matchWrap());
            return;
        }
        for (final DeviceInfo device : devices) {
            Button target = button(device.toString());
            target.setPadding(dp(14), dp(10), dp(14), dp(10));
            target.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { confirmSend(device); }
            });
            devicesContainer.addView(target, matchWrap());
        }
    }

    private void confirmSend(final DeviceInfo device) {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_select_files_first), Toast.LENGTH_SHORT).show();
            return;
        }
        long total = 0L;
        for (TransferFile file : selectedFiles) total += file.getSize();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_send_files))
                .setMessage(getString(R.string.dialog_msg_send_confirm, device.getAlias(), selectedFiles.size(), formatSize(total)))
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .setPositiveButton(getString(R.string.btn_start_sending), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showProgress(true, getString(R.string.status_waiting_recipient), "", 0, "");
                        app.sendFiles(device, new ArrayList<TransferFile>(selectedFiles));
                    }
                }).show();
    }

    @Override public void onReady(DeviceInfo self) {
        deviceName.setText(getString(R.string.label_device_name, self.getAlias()));
    }

    @Override public void onServiceChanged(boolean running, String detail) {
        serviceStatus.setText(detail);
        serviceButton.setText(running ? getString(R.string.btn_stop_receiving) : getString(R.string.btn_start_receiving));
    }

    @Override public void onDevicesChanged(List<DeviceInfo> devices) { renderDevices(devices); }

    @Override public void onIncoming(final IncomingSession session) {
        StringBuilder details = new StringBuilder();
        details.append(getString(R.string.label_sender, session.getSender().getAlias())).append('\n');
        details.append(getString(R.string.label_file_count, session.getFiles().size())).append('\n');
        details.append(getString(R.string.label_total_size, formatSize(session.getTotalBytes()))).append("\n\n");
        for (TransferFile file : session.getFiles()) {
            details.append(file.getFileName()).append("  ").append(formatSize(file.getSize())).append('\n');
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_title_incoming_request))
                .setMessage(details.toString())
                .setCancelable(false)
                .setNegativeButton(getString(R.string.btn_decline), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        app.decideIncoming(session, false);
                    }
                })
                .setPositiveButton(getString(R.string.btn_accept), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        app.decideIncoming(session, true);
                        showProgress(false, getString(R.string.status_waiting_sender), "", 0,
                                StorageUtils.receiveDirectory(MainActivity.this).getAbsolutePath());
                    }
                }).create();
        dialog.show();
    }

    @Override public void onTransferProgress(boolean sending, String title, String currentFile,
                                             int percent, String path) {
        showProgress(sending, title, currentFile, percent, path);
    }

    @Override public void onTransferResult(boolean sending, boolean success, String message) {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
        new AlertDialog.Builder(this)
                .setTitle(success ? (sending ? getString(R.string.status_send_success) : getString(R.string.status_receive_success)) : getString(R.string.status_transfer_failed))
                .setMessage(message)
                .setPositiveButton(getString(R.string.btn_ok), null)
                .show();
    }

    private void showProgress(final boolean sending, String title, String file, int percent, String path) {
        if (progressDialog == null) {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(22), dp(18), dp(22), dp(12));
            progressTitle = text(title, 18, Color.BLACK);
            progressFile = text(file, 14, Color.DKGRAY);
            progressPath = text(path.length() == 0 ? "" : getString(R.string.label_save_location, path), 12, Color.GRAY);
            progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            cancelTransfer = button(sending ? getString(R.string.btn_cancel_send) : getString(R.string.btn_cancel_receive));
            cancelTransfer.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (sending) app.cancelSending(); else app.cancelIncoming();
                    cancelTransfer.setEnabled(false);
                }
            });
            content.addView(progressTitle, matchWrap());
            content.addView(progressFile, matchWrap());
            content.addView(progressBar, matchWrap());
            content.addView(progressPath, matchWrap());
            content.addView(cancelTransfer, matchWrap());
            progressDialog = new Dialog(this);
            progressDialog.setTitle(sending ? getString(R.string.dialog_title_sending_status) : getString(R.string.dialog_title_receiving_status));
            progressDialog.setContentView(content);
            progressDialog.setCancelable(false);
            progressDialog.show();
            if (progressDialog.getWindow() != null) progressDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        progressTitle.setText(title);
        progressFile.setText(file.length() == 0 ? getString(R.string.status_preparing) : getString(R.string.label_current_file, file));
        progressPath.setText(path.length() == 0 ? "" : getString(R.string.label_save_location, path));
        progressBar.setProgress(percent);
    }

    private TextView section(String value) {
        TextView view = text(value, 18, Color.rgb(20, 80, 145));
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        return button;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private View space(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int index = -1;
        do { value /= 1024.0; index++; } while (value >= 1024.0 && index < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }
}
