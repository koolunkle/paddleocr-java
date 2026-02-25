# 📄 Document Structure Analysis & OCR Engine

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![OpenCV](https://img.shields.io/badge/OpenCV-Native-5C3EE8?style=for-the-badge&logo=opencv&logoColor=white)
![ONNX](https://img.shields.io/badge/ONNX-RapidOCR-005CED?style=for-the-badge&logo=onnx&logoColor=white)

A high-performance, robust Optical Character Recognition (OCR) and document parsing engine built with **Spring Boot**. This service not only extracts text from images and multi-page documents (TIFF/PNG) but also analyzes spatial coordinates to reconstruct table structures and parse domain-specific documents (e.g., Court Decisions) into structured JSON data.

## ✨ Key Features

* **⚡ High-Performance OCR Inference**
    * Powered by `RapidOCR` (ONNX Runtime) with C++ native bindings.
    * **Model Shadowing:** Seamlessly overrides default models to inject custom Korean recognition models (`PP-OCRv4`) and dictionaries for enhanced accuracy.
* **🧠 Smart Document Parsing**
    * `TableStructureEstimator`: Mathematically calculates X/Y bounding box coordinates to reconstruct complex 2D table structures (Rows & Cells).
    * `DecisionParser`: Domain-specific parser utilizing fuzzy string matching (Jaro-Winkler) and regex to extract key fields (Court Name, Case Number, Creditor, etc.) from legal documents.
* **🌊 Real-Time SSE Streaming**
    * Provides Server-Sent Events (SSE) for multi-page documents, pushing analysis results page-by-page in real-time to prevent HTTP timeouts and improve UX.
* **🛡️ Bulletproof Native Memory Management**
    * Implemented a custom `MatResourceWrapper` (AutoCloseable) to strictly control the lifecycle of OpenCV `Mat` objects, preventing catastrophic native memory leaks.
* **🏗️ Clean & Modern Architecture**
    * Extensive use of Java `record`s for immutable DTOs.
    * Stateless pure utility functions (`TextUtil`, `BoxUtil`).
    * Idiomatic Spring Boot `@ConfigurationProperties` binding.
    * Comprehensive Unit & Slice tests using **Mockito** and **JUnit 5**.

## 🚀 API Overview

### 1. Synchronous Analysis
Wait for the entire document to be processed and receive a complete JSON response.
* **Endpoint:** `POST /api/v1/ocr`
* **Consumes:** `multipart/form-data` (Supports images and multi-page `.tif`)
* **Produces:** `application/json`

### 2. Streaming Analysis (SSE)
Receive real-time events as each page of a multi-page document is processed.
* **Endpoint:** `POST /api/v1/ocr/stream`
* **Consumes:** `multipart/form-data`
* **Produces:** `text/event-stream`

## 📂 Architecture & Project Structure

The project follows a strict Layered Architecture to separate concerns and maximize testability:

    src/main/java/com/example/ocr/
     ├── config/      # App properties and inference configurations
     ├── controller/  # REST APIs and SSE endpoints
     ├── domain/      # Core domain models
     ├── dto/         # Immutable data transfer objects
     ├── parser/      # Heuristic & Rule-based text structure parsers
     ├── service/     # Business logic and AI model orchestration
     ├── support/     # Core infrastructure (Memory guards, SSE wrappers)
     └── util/        # Pure stateless mathematical & string functions

## 🛠️ Getting Started

### Prerequisites
* Java 17 or higher
* Gradle
* *(Optional)* Native OpenCV libraries installed on the host machine for local development.

### Installation & Run
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/your-repo-name.git
   ```
2. Build the project:
   ```bash
   ./gradlew clean build
   ```
3. Run the application:
   ```bash
   ./gradlew bootRun
   ```

## 🧪 Testing

The project includes a robust test suite covering pure logic, complex algorithms, and API layers using Mockito's deep stubbing and `MockedStatic` techniques.

```bash
# Run all Unit and WebMvc tests
./gradlew test
```

## 📝 License
This project is licensed under the MIT License - see the LICENSE file for details.
