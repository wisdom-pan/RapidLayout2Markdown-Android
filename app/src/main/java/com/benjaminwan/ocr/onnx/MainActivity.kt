package com.benjaminwan.ocr.onnx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.benjaminwan.ocr.onnx.databinding.ActivityMainBinding
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityMainBinding

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // TODO: 处理选中的图片 - OCR 功能
                Toast.makeText(this, "图片已选中: $uri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initViews() {
        binding.galleryBtn.setOnClickListener(this)
        binding.docScanBtn.setOnClickListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
    }

    override fun onClick(view: View?) {
        view ?: return
        when (view.id) {
            R.id.galleryBtn -> {
                startActivity(Intent(this, GalleryActivity::class.java))
            }
            R.id.docScanBtn -> {
                val intent = Intent(Intent.ACTION_PICK).apply {
                    type = "image/*"
                }
                galleryLauncher.launch(intent)
            }
            else -> {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
