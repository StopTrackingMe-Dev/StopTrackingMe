package app.stoptrackingme.qr

import androidx.core.content.FileProvider
import app.stoptrackingme.R

/** Dedicated provider avoids device-specific failures from instantiating FileProvider directly. */
class QrImageFileProvider : FileProvider(R.xml.qr_file_paths)
