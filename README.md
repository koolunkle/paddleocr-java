# Document Structure Analysis & OCR Engine

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![OpenCV](https://img.shields.io/badge/OpenCV-Native-5C3EE8?style=for-the-badge&logo=opencv&logoColor=white)
![ONNX](https://img.shields.io/badge/ONNX-RapidOCR-005CED?style=for-the-badge&logo=onnx&logoColor=white)

A high-performance Optical Character Recognition (OCR) and document parsing engine built with Spring Boot. This service extracts text from images and multi-page documents (TIFF/PNG), analyzes spatial coordinates to reconstruct logical structures, and parses domain-specific data into structured JSON.

## Key Features

* **High-Performance OCR Inference**
    * Powered by RapidOCR (ONNX Runtime) with C++ native bindings.
    * Model Shadowing: Seamlessly overrides default models to inject custom Korean recognition models (PP-OCRv4) and dictionaries.
* **Advanced Document & Table Parsing**
    * **SLANet Integration**: Utilizes the SLANet model to reconstruct logical table structures (HTML/Grid) and map OCR text to specific cells.
    * **Heuristic Estimator**: Fallback mechanism to calculate X/Y bounding box coordinates for 2D table reconstruction.
    * **DecisionParser**: Domain-specific parser using fuzzy string matching (Jaro-Winkler) and regex for structured data extraction from legal documents.
* **Real-Time SSE Streaming**
    * Server-Sent Events (SSE) support for multi-page document processing, providing page-by-page results in real-time.
* **Native Memory Management**
    * Custom `MatResourceWrapper` (AutoCloseable) to strictly manage the lifecycle of OpenCV Mat objects and prevent native memory leaks.
* **Architectural Standards**
    * Immutable DTOs using Java records.
    * Stateless utility functions for text and spatial operations.
    * Comprehensive test suite using JUnit 5 and Mockito.

## API Overview

### 1. Synchronous Analysis
Processes the entire document and returns a complete JSON response.
* **Endpoint:** `POST /api/v1/ocr`
* **Consumes:** `multipart/form-data` (Supports images and multi-page TIFF)
* **Produces:** `application/json`

### 2. Streaming Analysis (SSE)
Provides real-time updates as each page is processed.
* **Endpoint:** `POST /api/v1/ocr/stream`
* **Consumes:** `multipart/form-data`
* **Produces:** `text/event-stream`

## Project Structure

The project follows a layered architecture to ensure separation of concerns:

    src/main/java/com/example/ocr/
     ├── config/      # Application properties and inference configurations
     ├── controller/  # REST APIs and SSE endpoints
     ├── domain/      # Core domain models
     ├── dto/         # Immutable data transfer objects
     ├── parser/      # Heuristic and rule-based text structure parsers
     ├── service/     # Business logic and AI model orchestration
     ├── support/     # Infrastructure (Memory guards, SSE wrappers)
     └── util/        # Stateless mathematical and string utilities

## Getting Started

### Prerequisites
* Java 17 or higher
* Gradle
* (Optional) Native OpenCV libraries for local development environment

### Installation & Execution
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

## Testing

The project includes unit and integration tests covering core algorithms and API layers.

```bash
# Run all tests
./gradlew test
```

## License
This project is distributed under the Apache License 2.0. 
For details, see the [LICENSE](LICENSE) file.
